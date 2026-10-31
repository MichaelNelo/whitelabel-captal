# The change data capture relay.
#
# One stage, as the backoffice's: the jar is built by Mill and the image only packages it.
# `cdc.image` stages this file beside `cdc.jar` and runs the build.
FROM eclipse-temurin:21-jre-noble

WORKDIR /app

COPY cdc.jar cdc.jar

# HTTP, where rqlite posts its change data capture, on the `container_port` every service uses; and
# gRPC, where subscribers follow it. Both reached only inside the network.
EXPOSE 8080 9090
ENV CDC_PORT=8080
ENV CDC_GRPC_PORT=9090

CMD ["java", "-jar", "cdc.jar"]
