package app

/** The debugger's tests stop in this program: a loop, a caught and an uncaught exception, collections and a call. */
object Debugged {
  def main(args: Array[String]): Unit = {
    var total = 0
    for (i <- 1 to 5) {
      total += triple(i)
    }
    val numbers = List(1, 2, 3)
    val names = Map("one" -> 1, "two" -> 2)
    try fail("caught")
    catch { case _: IllegalStateException => () }
    println(s"total $total, ${numbers.sum}, ${names.size}")
    if (args.contains("throw")) fail("uncaught")
  }

  def triple(n: Int): Int = {
    val result = n * 3
    result
  }

  private def fail(message: String): Nothing = throw new IllegalStateException(message)
}
