package whitelabel.captal.core.operation

/** A run of an operation on a location — a deploy, a pause, a resume, a destroy — as the backoffice
  * records it.
  */
opaque type RunId = String

object RunId:
  def apply(value: String): RunId = value

  extension (id: RunId)
    def asString: String = id
