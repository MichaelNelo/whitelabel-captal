package whitelabel.captal.cli

import zio.*

/** Colored terminal output helpers. */
object Output:
  private val Reset = "\u001b[0m"
  private val Bold = "\u001b[1m"
  private val Cyan = "\u001b[36m"
  private val Green = "\u001b[32m"
  private val Red = "\u001b[31m"
  private val Yellow = "\u001b[33m"
  private val Blue = "\u001b[34m"
  private val Dim = "\u001b[2m"

  def step(n: Int, total: Int, msg: String): UIO[Unit] =
    Console.printLine(s"$Bold$Cyan[$n/$total]$Reset $msg").orDie

  // ASCII glyphs — rendered correctly in cmd.exe / PowerShell without UTF-8 codepage tweaks.
  // The previous Unicode versions (✓ ✗ ⚠ ℹ) showed as `?` on Windows consoles with the default
  // codepage (CP437/CP1252). ASCII versions are uglier but readable everywhere.
  def success(msg: String): UIO[Unit] = Console.printLine(s"$Green[OK]$Reset $msg").orDie

  def error(msg: String): UIO[Unit] = Console.printLine(s"$Red[X]$Reset $msg").orDie

  def warn(msg: String): UIO[Unit] = Console.printLine(s"$Yellow[!]$Reset $msg").orDie

  def info(msg: String): UIO[Unit] = Console.printLine(s"${Blue}[i]$Reset $msg").orDie

  def detail(msg: String): UIO[Unit] = Console.printLine(s"  $Dim$msg$Reset").orDie

  def header(msg: String): UIO[Unit] = Console.printLine(s"\n$Bold$msg$Reset").orDie
end Output
