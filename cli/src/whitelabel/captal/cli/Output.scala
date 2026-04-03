package whitelabel.captal.cli

import zio.*

/** Colored terminal output helpers. */
object Output:
  private val Reset  = "\u001b[0m"
  private val Bold   = "\u001b[1m"
  private val Cyan   = "\u001b[36m"
  private val Green  = "\u001b[32m"
  private val Red    = "\u001b[31m"
  private val Yellow = "\u001b[33m"
  private val Blue   = "\u001b[34m"
  private val Dim    = "\u001b[2m"

  def step(n: Int, total: Int, msg: String): UIO[Unit] =
    Console.printLine(s"$Bold$Cyan[$n/$total]$Reset $msg").orDie

  def success(msg: String): UIO[Unit] =
    Console.printLine(s"${Green}✓$Reset $msg").orDie

  def error(msg: String): UIO[Unit] =
    Console.printLine(s"${Red}✗$Reset $msg").orDie

  def warn(msg: String): UIO[Unit] =
    Console.printLine(s"${Yellow}⚠$Reset $msg").orDie

  def info(msg: String): UIO[Unit] =
    Console.printLine(s"${Blue}ℹ$Reset $msg").orDie

  def detail(msg: String): UIO[Unit] =
    Console.printLine(s"  $Dim$msg$Reset").orDie

  def header(msg: String): UIO[Unit] =
    Console.printLine(s"\n$Bold$msg$Reset").orDie
