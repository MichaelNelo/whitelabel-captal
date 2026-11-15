package whitelabel.captal.platform.locks

import zio.*
import zio.redis.{CodecSupplier, Input, Output, Redis, RedisConfig, Update}

/** A lock on a resource for a while: held by whoever took it with their `token` until they release
  * it, or until its time runs out if they stop extending it. The time is what frees a holder that
  * died; it is also what a holder that froze longer than it loses.
  */
trait DistributedLock:
  /** Takes `resource` for `token` for `ttl`: how long it is held for, or who holds it instead —
    * none when not enough instances answered to tell.
    */
  def acquire(resource: String, token: String, ttl: Duration): UIO[Either[Option[String], Duration]]

  /** Whether `token` still held `resource`, and holds it for `ttl` more. */
  def extend(resource: String, token: String, ttl: Duration): UIO[Boolean]

  /** Lets `resource` go, if `token` holds it: a lock someone else took since is left alone. */
  def release(resource: String, token: String): UIO[Unit]

/** The lock as Redis describes Redlock, on independent Redis instances (`nodes`):
  *
  *   - taken with `SET resource token NX PX ttl` on every instance at once, each asked for a short
  *     time; held when a majority took it and some time is left of `ttl`, less what taking it took
  *     and a margin for the clocks' drift — and, when not, let go on all of them;
  *   - extended and released by scripts that act only while the instance holds this token, so a
  *     lock that expired and was taken by another is never extended or deleted by its old holder.
  *
  * With one instance, it is Redis's lock on a single instance. An instance that cannot be reached
  * is a vote not given: the lock can still be held through the others.
  */
object Redlock:
  /** How long one instance is given to answer: short against the locks' times, so that one that is
    * down costs little of a lock's validity.
    */
  private val NodeTimeout = 500.millis

  /** How long connecting to an instance may take, apart from its answer: a first connection is a
    * name to resolve and a socket to open, which can take longer than an answer. Only an instance
    * not yet connected costs it, on each call until it connects.
    */
  private val ConnectTimeout = 2.seconds

  /** Redis's margin for the clocks' drift: a hundredth of the time, and two milliseconds. */
  private def drift(ttl: Duration): Duration = Duration.fromMillis(ttl.toMillis / 100 + 2)

  private val ReleaseScript =
    """if redis.call("get", KEYS[1]) == ARGV[1] then return redis.call("del", KEYS[1]) else return 0 end"""

  private val ExtendScript =
    """if redis.call("get", KEYS[1]) == ARGV[1] then return redis.call("pexpire", KEYS[1], ARGV[2]) else return 0 end"""

  private given Input[String] = Input.StringInput
  private given Output[Long] = Output.LongOutput

  /** One Redis instance, connected the first time it is asked rather than at startup: one that is
    * down then is a vote not given, not a backoffice that does not start.
    */
  final class Node private[Redlock] (
      config: RedisConfig,
      connected: Ref.Synchronized[Option[Redis]],
      scope: Scope):
    /** Each attempt in a scope of its own, closed when it fails, so attempts at an instance that is
      * down leave nothing open behind them.
      */
    def client: Task[Redis] = connected.modifyZIO:
      case Some(redis) =>
        ZIO.succeed(redis -> Some(redis))
      case None =>
        for
          attempt <- scope.fork
          redis   <-
          (ZLayer.succeed(CodecSupplier.utf8) ++ ZLayer.succeed(config) >>> Redis.singleNode)
            .build
            .provideEnvironment(ZEnvironment(attempt))
            .map(_.get[Redis])
            .timeoutFail(
              new java.util.concurrent.TimeoutException(s"no connection within $ConnectTimeout"))(
              ConnectTimeout)
            .onExit(exit => ZIO.when(exit.isFailure)(attempt.close(exit)))
        yield redis -> Some(redis)

    /** `ask` of this instance, within [[NodeTimeout]] once connected; none when it did not answer
      * in time.
      */
    def within[A](ask: Redis => Task[A]): UIO[Option[A]] = client
      .flatMap(ask(_).timeout(NodeTimeout))
      .catchAll(error =>
        ZIO
          .logWarning(s"Lock instance ${config.host}:${config.port}: ${error.getMessage}")
          .as(None))
  end Node

  def apply(nodes: List[Node]): DistributedLock =
    val quorum = nodes.size / 2 + 1
    new DistributedLock:
      def acquire(
          resource: String,
          token: String,
          ttl: Duration): UIO[Either[Option[String], Duration]] =
        for
          started <- Clock.nanoTime
          votes   <-
            ZIO.foreachPar(nodes)(_.within(_.set(resource, token, Some(ttl), Some(Update.SetNew))))
          ended <- Clock.nanoTime
          left = ttl.minus(Duration.fromNanos(ended - started)).minus(drift(ttl))
          held <-
            if votes.count(_.contains(true)) >= quorum && left.isPositive then
              ZIO.succeed(Right(left))
            else
              release(resource, token) *> holder(resource).map(Left(_))
        yield held

      def extend(resource: String, token: String, ttl: Duration): UIO[Boolean] = ZIO
        .foreachPar(nodes)(
          _.within(
            _.eval(ExtendScript, Chunk(resource), Chunk(token, ttl.toMillis.toString))
              .returning[Long]))
        .map(_.count(_.contains(1L)) >= quorum)

      def release(resource: String, token: String): UIO[Unit] =
        ZIO.foreachParDiscard(nodes)(
          _.within(_.eval(ReleaseScript, Chunk(resource), Chunk(token)).returning[Long]))

      /** The token most instances hold the resource under. */
      private def holder(resource: String): UIO[Option[String]] = ZIO
        .foreachPar(nodes)(_.within(_.get(resource).returning[String]))
        .map(_.flatten.flatten.groupBy(identity).maxByOption(_._2.size).map(_._1))
    end new
  end apply

  /** The instances at `REDIS_LOCK_ADDRESSES`, `host:port` separated by commas. None configured is a
    * lock nobody can take, which every operation is then refused for, naming the variable.
    */
  val layer: ZLayer[Any, Nothing, DistributedLock] = ZLayer.scoped:
    for
      addresses <- System
        .env("REDIS_LOCK_ADDRESSES")
        .orDie
        .map(_.toList.flatMap(_.split(',').toList).map(_.trim).filter(_.nonEmpty))
      _ <-
        ZIO.when(addresses.isEmpty)(
          ZIO.logWarning(
            "REDIS_LOCK_ADDRESSES is unset: no operation on a location can take its lock, so every one is refused"))
      scope <- ZIO.scope
      nodes <-
        ZIO.foreach(addresses): address =>
          val (host, port) =
            address.split(':') match
              case Array(host, port) =>
                (host, port.toInt)
              case _ =>
                (address, 6379)
          Ref.Synchronized.make(Option.empty[Redis]).map(Node(RedisConfig(host, port), _, scope))
    yield apply(nodes)
end Redlock
