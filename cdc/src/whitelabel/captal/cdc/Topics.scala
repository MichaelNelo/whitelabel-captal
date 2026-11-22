package whitelabel.captal.cdc

import java.net.URI

import scala.jdk.CollectionConverters.*

import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.{
  MessageAttributeValue,
  PublishBatchRequest,
  PublishBatchRequestEntry
}
import zio.*

/** The relay's topics, one per table, `<prefix><table>`: each change set is published whole to the
  * topic of every table its events touch, the message the change set as rqlite wrote it, with its
  * index and the trace it was received in as message attributes. A subscriber's queue reads the
  * index from the attribute, so it never has to parse the message to know it is a change set.
  *
  * A publish the topic refuses fails the whole post, which rqlite then posts again. A table with no
  * topic is one: rqlite publishes what its filter names, and the configuration gives every table it
  * names a topic.
  */
final class Topics(sns: SnsClient, prefix: String):
  def publish(changes: List[Change]): Task[Unit] =
    ZIO.foreachDiscard(
      changes.flatMap(change => change.tables.map(_ -> change)).groupMap(_._1)(_._2).toList):
      (table, sets) => ZIO.foreachDiscard(Topics.batches(sets))(send(prefix + table, _))

  private def send(topic: String, batch: List[Change]): Task[Unit] =
    for
      answer <- ZIO.attemptBlocking(
        sns.publishBatch(
          PublishBatchRequest
            .builder()
            .topicArn(topic)
            .publishBatchRequestEntries(batch.map(Topics.entry).asJava)
            .build()))
      refused = answer.failed.asScala.toList
      _ <-
        ZIO.when(refused.nonEmpty)(
          ZIO.fail(
            IllegalStateException(
              s"$topic refused ${refused.size} change set(s): ${refused
                  .map(_.message)
                  .distinct
                  .mkString("; ")}")))
    yield ()
end Topics

object Topics:
  /** A batch takes ten messages and 256 KiB in all; this leaves room for the attributes. */
  val MaxBatch = 10
  val MaxBatchBytes: Int = 200 * 1024

  /** The topics at `prefix`, their client closed with the scope. The endpoint is set explicitly, as
    * every AWS client here is.
    */
  def make(prefix: String): ZIO[Scope, Throwable, Topics] =
    for
      endpoint <- System.env("AWS_ENDPOINT_URL").map(_.map(_.trim).filter(_.nonEmpty))
      sns      <- ZIO.fromAutoCloseable(
        ZIO.attempt(
          endpoint.fold(SnsClient.create())(url =>
            SnsClient.builder().endpointOverride(URI.create(url)).build())))
    yield Topics(sns, prefix)

  /** The change sets of one topic in index order, in batches of at most [[MaxBatch]] and
    * [[MaxBatchBytes]]; one larger than that alone goes in a batch of its own, which the topic
    * refuses if it is over its limit.
    */
  def batches(sets: List[Change]): List[List[Change]] =
    def size(change: Change) = change.json.getBytes("UTF-8").length
    sets
      .sortBy(_.index)
      .foldLeft(List.empty[List[Change]]):
        case (current :: done, change)
            if current.size < MaxBatch && current.map(size).sum + size(change) <= MaxBatchBytes =>
          (change :: current) :: done
        case (done, change) =>
          List(change) :: done
      .map(_.reverse)
      .reverse

  private def entry(change: Change): PublishBatchRequestEntry = PublishBatchRequestEntry
    .builder()
    .id(change.index.toString)
    .message(change.json)
    .messageAttributes(attributes(change).asJava)
    .build()

  /** `index`, and the W3C trace context headers the post was received in. */
  def attributes(change: Change): Map[String, MessageAttributeValue] =
    Map(
      "index" ->
        MessageAttributeValue
          .builder()
          .dataType("Number")
          .stringValue(change.index.toString)
          .build()) ++
      change
        .context
        .collect:
          case (name @ ("traceparent" | "tracestate"), value) if value.nonEmpty =>
            name -> MessageAttributeValue.builder().dataType("String").stringValue(value).build()
end Topics
