package whitelabel.captal.core

import cats.data.{Chain, NonEmptyChain, WriterT}
import cats.syntax.either.*

/** Domain operation that can fail and emit events.
  *
  * Uses WriterT over Either with Chain for O(1) event accumulation.
  * Structure: Either[NEC[E], (Chain[Evt], A)]
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
type Op[Evt, E, A] = WriterT[[X] =>> Either[NonEmptyChain[E], X], Chain[Evt], A]

object Op:
  /** Pure value without events or error */
  def pure[Evt, E, A](a: A): Op[Evt, E, A] = WriterT.value(a)

  /** Emit an event along with a value */
  def emit[Evt, E, A](event: Evt, a: A): Op[Evt, E, A] = WriterT(Right((Chain.one(event), a)))

  /** Emit an event without value (Unit) */
  def emit[Evt, E](event: Evt): Op[Evt, E, Unit] = emit(event, ())

  /** Emit multiple events along with a value */
  def emitAll[Evt, E, A](events: Seq[Evt], a: A): Op[Evt, E, A] =
    WriterT(Right((Chain.fromSeq(events), a)))

  /** Fail with a single error */
  def fail[Evt, E, A](err: E): Op[Evt, E, A] = WriterT(Left(NonEmptyChain.one(err)))

  /** Fail with multiple errors */
  def failAll[Evt, E, A](errs: NonEmptyChain[E]): Op[Evt, E, A] = WriterT(Left(errs))

  /** Conditionally fail or return Unit */
  def failIf[Evt, E](condition: Boolean, err: => E): Op[Evt, E, Unit] =
    if condition then fail(err)
    else pure(())

  /** Conditionally fail or return Unit */
  def failUnless[Evt, E](condition: Boolean, err: => E): Op[Evt, E, Unit] = failIf(!condition, err)

  /** Lift an Either into Op */
  def fromEither[Evt, E, A](either: Either[E, A]): Op[Evt, E, A] =
    WriterT(either.bimap(NonEmptyChain.one, a => (Chain.empty, a)))

  /** Lift an Option into Op with error if None */
  def fromOption[Evt, E, A](opt: Option[A], ifNone: => E): Op[Evt, E, A] =
    opt match
      case Some(a) => pure(a)
      case None    => fail(ifNone)

  /** Run the operation and extract the result as List */
  def run[Evt, E, A](op: Op[Evt, E, A]): Either[NonEmptyChain[E], (List[Evt], A)] =
    op.run.map((chain, a) => (chain.toList, a))
