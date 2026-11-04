package whitelabel.captal.core.device

/** A MAC address as the unsigned 48-bit integer its six bytes spell, most significant first:
  * `aa:bb:cc:dd:ee:ff` is `0xaabbccddeeff`. One value however the address was written, so two
  * spellings of one device are one device.
  */
opaque type Mac = Long

object Mac:
  private val Bits = 48
  private val Max = (1L << Bits) - 1

  /** Twelve hex digits, in pairs separated by `:` or `-` or not at all, in either case. Anything
    * else is not a MAC.
    */
  def parse(text: String): Option[Mac] =
    val digits = text.trim.replace(":", "").replace("-", "")
    Option.when(digits.length == 12 && digits.forall(Character.digit(_, 16) >= 0))(
      java.lang.Long.parseLong(digits, 16))

  /** The stored integer, when it is one a MAC can be. */
  def fromLong(value: Long): Option[Mac] = Option.when(value >= 0 && value <= Max)(value)

  def unsafe(value: Long): Mac =
    require(value >= 0 && value <= Max, s"not a 48-bit MAC: $value")
    value

  extension (mac: Mac)
    def toLong: Long = mac

    /** Lower case, colon-separated: how UniFi writes it. */
    def show: String = (0 until 6).map(i => f"${(mac >>> (8 * (5 - i))) & 0xff}%02x").mkString(":")
end Mac
