package whitelabel.captal.messages

import io.circe.generic.semiauto.*
import io.circe.{Decoder as CirceDecoder, Encoder as CirceEncoder}

/** The exchange that offers a guest a way out of an error and lets them take it. Their server-side
  * `Schema` instances are in `api.wire`.
  */
final case class ErrorFrame(file: String, line: Int, column: Int)

object ErrorFrame:
  given CirceEncoder[ErrorFrame] = deriveEncoder
  given CirceDecoder[ErrorFrame] = deriveDecoder

/** What the page says it caught: the bundle it runs, the error's `name` and message, and the stack
  * as frames.
  */
final case class ClientErrorReport(
    bundleVersion: String,
    name: String,
    message: String,
    frames: List[ErrorFrame])

object ClientErrorReport:
  given CirceEncoder[ClientErrorReport] = deriveEncoder
  given CirceDecoder[ClientErrorReport] = deriveDecoder

/** Exactly one of the two: the id of an error the server answered with, or a report of one the page
  * caught.
  */
final case class RecoveryRequest(serverErrorId: Option[String], report: Option[ClientErrorReport])

object RecoveryRequest:
  given CirceEncoder[RecoveryRequest] = deriveEncoder
  given CirceDecoder[RecoveryRequest] = deriveDecoder

/** The way out, open from `availableAt`; `waitSeconds` is the same, counted from the answer, for a
  * page whose clock cannot be trusted. `recurrent` when other devices reported the same error,
  * which is why a wait shortens while the page counts it down.
  */
final case class RecoveryOffer(
    errorId: String,
    availableAt: String,
    waitSeconds: Long,
    recurrent: Boolean)

object RecoveryOffer:
  given CirceEncoder[RecoveryOffer] = deriveEncoder
  given CirceDecoder[RecoveryOffer] = deriveDecoder

/** Where the way out stands now, asked by the waiting page. */
final case class CheckRecoveryRequest(errorId: String)

object CheckRecoveryRequest:
  given CirceEncoder[CheckRecoveryRequest] = deriveEncoder
  given CirceDecoder[CheckRecoveryRequest] = deriveDecoder

final case class RedeemRecoveryRequest(errorId: String)

object RedeemRecoveryRequest:
  given CirceEncoder[RedeemRecoveryRequest] = deriveEncoder
  given CirceDecoder[RedeemRecoveryRequest] = deriveDecoder
