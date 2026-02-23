package whitelabel.captal.core

import cats.data.{Chain, NonEmptyChain, WriterT}
import cats.syntax.either.*
import cats.{Monad, Parallel}

/** Domain operation that can fail and emit events.
  *
  * Uses WriterT over Either with Chain for O(1) event accumulation. Structure: Either[NEC[E],
  * (Chain[Evt], A)]
  *   - Events are only produced on success
  *   - Sequential operations (flatMap) are fail-fast
  *   - Parallel operations (parMapN) accumulate errors
  *
  * @tparam Evt
  *   Event type to emit
  * @tparam E
  *   Error type
  * @tparam A
  *   Result type
  */
opaque type Op[Evt, E, A] = WriterT[[X] =>> Either[NonEmptyChain[E], X], Chain[Evt], A]

object Op:
  def pure[Evt, E, A](a: A): Op[Evt, E, A] = WriterT.value(a)
  def emit[Evt, E, A](event: Evt, a: A): Op[Evt, E, A] = WriterT(Right((Chain.one(event), a)))
  def emit[Evt, E](event: Evt): Op[Evt, E, Unit] = emit(event, ())
  def emitAll[Evt, E, A](events: Seq[Evt], a: A): Op[Evt, E, A] = WriterT(
    Right((Chain.fromSeq(events), a)))
  def fail[Evt, E, A](err: E): Op[Evt, E, A] = WriterT(Left(NonEmptyChain.one(err)))
  def failAll[Evt, E, A](errs: NonEmptyChain[E]): Op[Evt, E, A] = WriterT(Left(errs))
  def failIf[Evt, E](condition: Boolean, err: => E): Op[Evt, E, Unit] =
    if condition then
      fail(err)
    else
      pure(())
  def failUnless[Evt, E](condition: Boolean, err: => E): Op[Evt, E, Unit] = failIf(!condition, err)
  def fromEither[Evt, E, A](either: Either[E, A]): Op[Evt, E, A] = WriterT(
    either.bimap(NonEmptyChain.one, a => (Chain.empty, a)))
  def fromOption[Evt, E, A](opt: Option[A], ifNone: => E): Op[Evt, E, A] =
    opt match
      case Some(a) =>
        pure(a)
      case None =>
        fail(ifNone)
  def run[Evt, E, A](op: Op[Evt, E, A]): Either[NonEmptyChain[E], (List[Evt], A)] = op
    .run
    .map((chain, a) => (chain.toList, a))

  given opMonad[Evt, E]: Monad[[A] =>> Op[Evt, E, A]] with
    def pure[A](a: A): Op[Evt, E, A] = Op.pure(a)

    def flatMap[A, B](fa: Op[Evt, E, A])(f: A => Op[Evt, E, B]): Op[Evt, E, B] = fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Op[Evt, E, Either[A, B]]): Op[Evt, E, B] =
      Monad[[A] =>> Op[Evt, E, A]].tailRecM(a)(f)

  given opParallel[Evt, E]: Parallel[[A] =>> Op[Evt, E, A]] =
    val inner = Parallel[[A] =>> Op[Evt, E, A]]
    new Parallel[[A] =>> Op[Evt, E, A]]:
      type F[A] = inner.F[A]
      def applicative = inner.applicative
      def monad = opMonad[Evt, E]
      def sequential = inner.sequential
      def parallel = inner.parallel

  extension [Evt, E, A](op: Op[Evt, E, A])
    def convertEvent[Evt2](using conv: Conversion[Evt, Evt2]): Op[Evt2, E, A] = WriterT(
      op.run.map((chain, a) => (chain.map(conv(_)), a)))

    def convertError[E2](using conv: Conversion[NonEmptyChain[E], E2]): Op[Evt, E2, A] = WriterT(
      op.run.left.map(errs => NonEmptyChain.one(conv(errs))))
end Op
