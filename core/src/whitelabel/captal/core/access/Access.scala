package whitelabel.captal.core.access

import java.time.Instant

import whitelabel.captal.core.user

/** The network access a session is granted: what the controller authorizes is the session's device,
  * and what expiring resets is the session. A user is incidental to it — a session that left
  * through an error before giving an email has none.
  */
enum Event:
  /** The session is granted access: the controller is asked to authorize its device. Emitted by
    * `finish`, again on each retry while the controller has not confirmed.
    */
  case AccessGranted(sessionId: user.SessionId, userId: Option[user.Id], occurredAt: Instant)

  /** The access reached its end: the controller revokes it and the session starts over. */
  case AccessExpired(sessionId: user.SessionId, userId: Option[user.Id], occurredAt: Instant)
