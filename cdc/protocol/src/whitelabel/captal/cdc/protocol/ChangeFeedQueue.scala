package whitelabel.captal.cdc.protocol

import java.net.URI
import java.security.MessageDigest
import java.util.UUID

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.{
  PublishRequest,
  SubscribeRequest as SnsSubscribe,
  UnsubscribeRequest
}
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.{
  CreateQueueRequest,
  DeleteMessageBatchRequest,
  DeleteMessageBatchRequestEntry,
  DeleteQueueRequest,
  GetQueueAttributesRequest,
  Message,
  QueueAttributeName,
  ReceiveMessageRequest,
  SetQueueAttributesRequest
}
import zio.*
import zio.stream.ZStream

import ChangeFeedFollower.Event

/** Following the change feed on a queue of this task's own, subscribed to the relay's topic for
  * each table it reads, `<topic prefix><table>`.
  *
  * A queue per task: tasks sharing one would split its messages, and a deploy runs the outgoing
  * task beside the incoming one. So the queue is made as the stream starts, named `<queue
  * prefix>-<service>- <random id>`, subscribed with raw delivery, and unsubscribed and deleted as
  * its scope closes — on SIGTERM, through the shutdown hook `ZIOAppDefault` registers. A task
  * killed otherwise leaves its queue behind.
  *
  * Before anything is read, the task publishes a marker to each topic and drains its queue until
  * every one has come back: the subscription delivers. What arrived before the marker was committed
  * before then, so the rebuild that follows reads it: the stream's first event is a
  * [[ChangeFeedFollower.Event.Gap]], which has a subscriber rebuild from the database, and every
  * change after it is in the queue. A change set is a message whose `index` attribute the relay
  * set; the others' markers carry none and are passed over.
  *
  * It never fails and never ends: whatever stops a queue is logged, and a new one is made, with
  * another gap. Messages are deleted as they are received; they are only ever read once, and a task
  * that dies before handling one rebuilds on its next queue anyway.
  */
object ChangeFeedQueue:
  /** @param topicPrefix
    *   the ARN of a table's topic, but for the table's name.
    * @param queuePrefix
    *   what every task's queue is named under.
    * @param deadLetterArn
    *   the queue a message goes to after [[MaxReceives]] receipts it was not deleted after.
    */
  final case class Settings(topicPrefix: String, queuePrefix: String, deadLetterArn: Option[String])

  val RetryAfter: Duration = 2.seconds

  /** How long the markers have to come back before the queue is given up on and another made. */
  val MarkerWait: Duration = 1.minute

  /** How long a received message stays hidden, before it is read again if it was not deleted. */
  val VisibilitySeconds = 5

  val MaxReceives = 5

  /** A queue name has at most 80 characters. */
  val MaxName = 80

  /** `CDC_TOPIC_PREFIX`, `CDC_QUEUE_PREFIX` and `CDC_DEAD_LETTER_ARN`; none without the first two,
    * and then a task follows the relay over gRPC, or not at all.
    */
  def fromEnv: UIO[Option[Settings]] =
    def read(name: String) = System.env(name).orDie.map(_.map(_.trim).filter(_.nonEmpty))
    for
      topics     <- read("CDC_TOPIC_PREFIX")
      queues     <- read("CDC_QUEUE_PREFIX")
      deadLetter <- read("CDC_DEAD_LETTER_ARN")
    yield topics.zip(queues).map((t, q) => Settings(t, q, deadLetter))

  /** @param service
    *   `mcp` or a location's slug: whose tasks' queues these are.
    * @param tables
    *   the tables whose change sets this task reads.
    * @param retention
    *   how long a message waits unread before it is dropped: longer than this task could fall
    *   behind.
    */
  def follow(
      settings: Settings,
      service: String,
      tables: List[String],
      retention: Duration): ZStream[Any, Nothing, Event] =
    val once = ZStream
      .unwrapScoped(subscribed(settings, service, tables, retention))
      .catchAllCause(cause =>
        ZStream
          .fromZIO(
            ZIO.logWarning(s"Change feed queue of $service dropped: ${cause.squash.getMessage}"))
          .drain)
    (once ++ ZStream.fromZIO(ZIO.sleep(RetryAfter)).drain).forever

  /** What every queue of `service` is named under, `<prefix>-<service>-`, the service shortened
    * with a digest where the name would be too long; a queue's name adds 32 hexadecimal characters.
    */
  def namePrefix(prefix: String, service: String): String =
    val room = MaxName - prefix.length - 2 - 32
    val short =
      if service.length <= room then
        service
      else
        val digest =
          MessageDigest
            .getInstance("SHA-256")
            .digest(service.getBytes("UTF-8"))
            .take(2)
            .map(b => f"${b & 0xff}%02x")
            .mkString
        s"${service.take(room - 5)}-$digest"
    s"$prefix-$short-"

  /** Whether `name` is one of `service`'s queues, rather than one of a service its name extends. */
  def isQueueOf(prefix: String, service: String, name: String): Boolean =
    val under = namePrefix(prefix, service)
    name.startsWith(under) && name.drop(under.length).matches("[0-9a-f]{32}")

  private def subscribed(
      settings: Settings,
      service: String,
      tables: List[String],
      retention: Duration): ZIO[Scope, Throwable, ZStream[Any, Throwable, Event]] =
    for
      endpoint <- System.env("AWS_ENDPOINT_URL").map(_.map(_.trim).filter(_.nonEmpty))
      sqs      <- ZIO.fromAutoCloseable(ZIO.attempt(sqsClient(endpoint)))
      sns      <- ZIO.fromAutoCloseable(ZIO.attempt(snsClient(endpoint)))
      name   = namePrefix(settings.queuePrefix, service) + UUID.randomUUID.toString.replace("-", "")
      topics = tables.map(settings.topicPrefix + _)
      url <-
        ZIO.acquireRelease(create(sqs, name, retention, settings.deadLetterArn))(url =>
          ZIO
            .attemptBlocking(sqs.deleteQueue(DeleteQueueRequest.builder().queueUrl(url).build()))
            .tapError(e => ZIO.logWarning(s"Could not delete queue $name: ${e.getMessage}"))
            .ignore *> ZIO.logInfo(s"Deleted queue $name"))
      arn <- ZIO.attemptBlocking(
        sqs
          .getQueueAttributes(
            GetQueueAttributesRequest
              .builder()
              .queueUrl(url)
              .attributeNames(QueueAttributeName.QUEUE_ARN)
              .build())
          .attributes()
          .get(QueueAttributeName.QUEUE_ARN))
      _ <- ZIO.attemptBlocking(
        sqs.setQueueAttributes(
          SetQueueAttributesRequest
            .builder()
            .queueUrl(url)
            .attributes(Map(QueueAttributeName.POLICY -> policy(arn, topics)).asJava)
            .build()))
      _ <-
        ZIO.foreachDiscard(topics)(topic =>
          ZIO.acquireRelease(subscribe(sns, topic, arn))(subscription =>
            ZIO
              .attemptBlocking(
                sns.unsubscribe(UnsubscribeRequest.builder().subscriptionArn(subscription).build()))
              .tapError(e => ZIO.logWarning(s"Could not unsubscribe $name: ${e.getMessage}"))
              .ignore))
      marker = s"""{"marker":"$name"}"""
      _ <-
        ZIO.foreachDiscard(topics)(topic =>
          ZIO.attemptBlocking(
            sns.publish(PublishRequest.builder().topicArn(topic).message(marker).build())))
      _ <-
        awaitMarkers(sqs, url, marker, topics.size).timeoutFail(
          IllegalStateException(s"its markers did not come back within ${MarkerWait.render}"))(
          MarkerWait)
      _ <- ZIO.logInfo(s"Following ${tables.mkString(", ")} on queue $name")
    yield ZStream.succeed(Event.Gap) ++ ZStream.repeatZIO(receive(sqs, url, 20)).flattenIterables

  private def create(
      sqs: SqsClient,
      name: String,
      retention: Duration,
      deadLetter: Option[String]): Task[String] =
    val attributes =
      Map(
        QueueAttributeName.MESSAGE_RETENTION_PERIOD -> retention.toSeconds.toString,
        QueueAttributeName.VISIBILITY_TIMEOUT       -> VisibilitySeconds.toString) ++
        deadLetter.map(arn =>
          QueueAttributeName.REDRIVE_POLICY ->
            s"""{"deadLetterTargetArn":"$arn","maxReceiveCount":"$MaxReceives"}""")
    ZIO.attemptBlocking(
      sqs
        .createQueue(
          CreateQueueRequest.builder().queueName(name).attributes(attributes.asJava).build())
        .queueUrl())

  /** The topics may send to the queue, and nothing else. */
  private def policy(queue: String, topics: List[String]): String =
    val sources = topics.map(t => s"\"$t\"").mkString(",")
    s"""{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},"Action":"sqs:SendMessage","Resource":"$queue","Condition":{"ArnEquals":{"aws:SourceArn":[$sources]}}}]}"""

  private def subscribe(sns: SnsClient, topic: String, queue: String): Task[String] = ZIO
    .attemptBlocking(
      sns
        .subscribe(
          SnsSubscribe
            .builder()
            .topicArn(topic)
            .protocol("sqs")
            .endpoint(queue)
            .attributes(Map("RawMessageDelivery" -> "true").asJava)
            .returnSubscriptionArn(true)
            .build())
        .subscriptionArn())

  /** Drains the queue until `count` copies of `marker` arrived, one per topic. */
  private def awaitMarkers(sqs: SqsClient, url: String, marker: String, count: Int): Task[Unit] =
    def loop(seen: Int): Task[Unit] =
      if seen >= count then
        ZIO.unit
      else
        received(sqs, url, 1).flatMap(messages => loop(seen + messages.count(_.body == marker)))
    loop(0)

  /** The change sets of one long poll, the messages deleted. */
  private def receive(sqs: SqsClient, url: String, waitSeconds: Int): Task[List[Event]] = received(
    sqs,
    url,
    waitSeconds).map(_.flatMap(changeOf))

  private def received(sqs: SqsClient, url: String, waitSeconds: Int): Task[List[Message]] =
    for
      messages <- ZIO.attemptBlockingInterrupt(
        sqs
          .receiveMessage(
            ReceiveMessageRequest
              .builder()
              .queueUrl(url)
              .maxNumberOfMessages(10)
              .waitTimeSeconds(waitSeconds)
              .messageAttributeNames("All")
              .build())
          .messages()
          .asScala
          .toList)
      _ <-
        ZIO.when(messages.nonEmpty)(
          ZIO.attemptBlocking(
            sqs.deleteMessageBatch(
              DeleteMessageBatchRequest
                .builder()
                .queueUrl(url)
                .entries(
                  messages
                    .zipWithIndex
                    .map((message, i) =>
                      DeleteMessageBatchRequestEntry
                        .builder()
                        .id(i.toString)
                        .receiptHandle(message.receiptHandle)
                        .build())
                    .asJava)
                .build())))
    yield messages

  /** A change set, with the trace the relay received it in; none for a message without an index. */
  private def changeOf(message: Message): Option[Event] =
    val attributes = message.messageAttributes.asScala
    def attribute(name: String) = attributes.get(name).flatMap(a => Option(a.stringValue))
    attribute("index")
      .flatMap(_.toLongOption)
      .map: index =>
        val context = List("traceparent", "tracestate").flatMap(name =>
          attribute(name).map(name -> _))
        Event.Changed(index, message.body, context.toMap)

  /** The endpoint set explicitly, as every AWS client here is. */
  private def sqsClient(endpoint: Option[String]): SqsClient =
    endpoint.fold(SqsClient.create())(url =>
      SqsClient.builder().endpointOverride(URI.create(url)).build())

  private def snsClient(endpoint: Option[String]): SnsClient =
    endpoint.fold(SnsClient.create())(url =>
      SnsClient.builder().endpointOverride(URI.create(url)).build())
end ChangeFeedQueue
