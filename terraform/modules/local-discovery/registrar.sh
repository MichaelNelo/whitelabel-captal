# Keeps each named service's A record in the discovery zone listing the addresses of its running
# tasks, every one of them and them alone, as ECS has Cloud Map do where there is one. A service is
# named by its `DiscoveryName` tag; a task's address is its container's. Every INTERVAL seconds, for
# as long as it runs. A call that fails changes nothing, and the next round tries again: a failed
# listing must not read as a service with no task, which would delete its name.
#
# Run by `modules/local-discovery`, as the command of the AWS CLI's image: CLUSTER, ZONE_ID and
# ZONE_NAME come from the task definition, the endpoint from LocalStack.
set -u

domain="${ZONE_NAME%.}."
interval="${INTERVAL:-2}"

# The values of a space-separated list, sorted, one space apart: two lists compare as strings.
sorted() { tr '\t ' '\n\n' | grep -v -e '^$' -e '^None$' | sort | tr '\n' ' ' | sed 's/ $//'; }

# Leaves the record of service $1, named $2, listing its running tasks' addresses.
sync() {
  local service=$1 fqdn="$2.$domain" arns want have values action
  arns=$(aws ecs list-tasks --cluster "$CLUSTER" --service-name "$service" \
    --desired-status RUNNING --query 'taskArns' --output text) || return
  want=""
  if [ -n "$(echo "$arns" | sorted)" ]; then
    # shellcheck disable=SC2086 # one argument per task
    want=$(aws ecs describe-tasks --cluster "$CLUSTER" --tasks $arns \
      --query "tasks[?lastStatus=='RUNNING'].containers[0].networkInterfaces[0].privateIpv4Address" \
      --output text) || return
    want=$(echo "$want" | sorted)
  fi
  have=$(aws route53 list-resource-record-sets --hosted-zone-id "$ZONE_ID" \
    --query "ResourceRecordSets[?Name=='$fqdn' && Type=='A'].ResourceRecords[].Value" \
    --output text) || return
  have=$(echo "$have" | sorted)
  [ "$want" = "$have" ] && return

  # DELETE names the record set as it stands; UPSERT writes it whole.
  if [ -z "$want" ]; then action=DELETE; values=$have; else action=UPSERT; values=$want; fi
  values=$(for address in $values; do printf '{"Value":"%s"},' "$address"; done)
  aws route53 change-resource-record-sets --hosted-zone-id "$ZONE_ID" --output text \
    --query 'ChangeInfo.Status' --change-batch \
    "{\"Changes\":[{\"Action\":\"$action\",\"ResourceRecordSet\":{\"Name\":\"$fqdn\",\"Type\":\"A\",\"TTL\":10,\"ResourceRecords\":[${values%,}]}}]}" \
    > /dev/null && echo "$fqdn: ${have:-none} -> ${want:-none}"
}

echo "Keeping the names of the services in $CLUSTER in $domain, every ${interval}s"
while true; do
  if services=$(aws ecs list-services --cluster "$CLUSTER" --query 'serviceArns' --output text); then
    # DescribeServices takes ten at a time.
    set -- $services
    while [ $# -gt 0 ]; do
      batch=("${@:1:10}")
      shift $(( $# < 10 ? $# : 10 ))
      aws ecs describe-services --cluster "$CLUSTER" --services "${batch[@]}" --include TAGS \
        --query "services[].[serviceName, (tags[?key=='DiscoveryName'].value)[0]]" --output text |
        while read -r service name; do
          [ -n "$name" ] && [ "$name" != None ] && sync "$service" "$name"
        done
    done
  fi
  sleep "$interval"
done
