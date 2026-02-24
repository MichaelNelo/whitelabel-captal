package whitelabel.captal.core.application

import cats.MonadThrow
import cats.data.NonEmptyChain
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import whitelabel.captal.core.Op
import whitelabel.captal.core.application.commands.Handler

trait Flow[F[_], C]:
  type Result
  def execute(command: C): F[Result]

object Flow:
  type Aux[F[_], C, R] =
    Flow[F, C] {
      type Result = R
    }

  final case class HandlerError(errors: NonEmptyChain[Error])
      extends Exception(errors.head.toString)

  def apply[F[_]: MonadThrow, C, R](
      handler: Handler.Aux[F, C, R],
      eventHandler: EventHandler[F, Event]): Flow.Aux[F, C, R] =
    new Flow[F, C]:
      type Result = R

      def execute(command: C): F[R] =
        for
          opResult <- handler.handle(command)
          result   <- MonadThrow[F].fromEither(Op.run(opResult).left.map(HandlerError(_)))
          (events, value) = result
          _ <- eventHandler.handle(events)
        yield value
end Flow
