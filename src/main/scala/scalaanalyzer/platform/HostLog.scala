package scalaanalyzer.platform

import java.io.{PrintWriter, StringWriter}
import java.time.{Instant, ZoneId}
import java.time.format.DateTimeFormatter
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
import java.util.concurrent.atomic.AtomicLong

/**
 * Collects host, language-server and platform log messages.
 *
 * Entries go to the sinks added (the language server's log file and its client);
 * with none, to stderr. Each level's entries are counted, and the latest warnings and errors kept, for status reports.
 */
object HostLog {
  /** Severity order used by the threshold and warning/error summaries. */
  enum Level { case Debug, Info, Warn, Error }

  /**
   * A timestamped host log message.
   *
   * @param time instant at which the message was accepted
   * @param level message severity
   * @param message text including the rendered stack trace when a cause was supplied
   */
  final case class Entry(time: Instant, level: Level, message: String) {
    override def toString: String = s"${HostLog.TimeFormat.format(time)} ${level.toString.toUpperCase.padTo(5, ' ')} $message"
  }

  private val TimeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
  // Enough recent warnings and errors to see what went wrong without reading the log file.
  private val RecentProblems = 50

  /** Entries below this level are dropped (`SCALA_ANALYZER_LOG=debug` in the language server). */
  @volatile var threshold: Level = Level.Info

  private val sinks = new CopyOnWriteArrayList[Entry => Unit]()
  private val counts = new ConcurrentHashMap[Level, AtomicLong]()
  private val recent = new java.util.ArrayDeque[Entry]()

  /**
   * Registers a sink for accepted messages.
   *
   * Sinks run synchronously on the logging thread and may be called concurrently. Closing the registration
   * removes the sink without closing resources it owns; an in-flight call may still complete.
   *
   * @param sink callback receiving each accepted entry
   * @return a registration that removes the callback when closed
   */
  def addSink(sink: Entry => Unit): AutoCloseable = {
    sinks.add(sink)
    () => sinks.remove(sink)
  }

  /** Whether debug messages currently pass the logging threshold. */
  def isDebug: Boolean = threshold == Level.Debug

  /**
   * Writes a lazily constructed debug message.
   *
   * @param message text evaluated only when debug logging is enabled
   */
  def debug(message: => String): Unit = if (isDebug) log(Level.Debug, message, null)

  /**
   * Writes an informational message to the current sinks.
   *
   * @param message text to log if the threshold accepts informational messages
   */
  def info(message: String): Unit = log(Level.Info, message, null)

  /**
   * Writes a warning with an optional stack trace.
   *
   * @param message warning text
   * @param cause failure to render, or null when absent
   */
  def warn(message: String, cause: Throwable = null): Unit = log(Level.Warn, message, cause)

  /**
   * Writes an error with an optional stack trace.
   *
   * @param message error text
   * @param cause failure to render, or null when absent
   */
  def error(message: String, cause: Throwable = null): Unit = log(Level.Error, message, cause)

  /**
   * Runs an operation and logs its elapsed time after success.
   *
   * Failures propagate without a timing entry.
   *
   * @tparam T operation result type
   * @param what operation label for the informational timing message
   * @param body operation to measure
   * @return the operation's result
   */
  def timed[T](what: String)(body: => T): T = {
    val start = System.nanoTime()
    val result = body
    info(s"$what in ${(System.nanoTime() - start) / 1_000_000} ms")
    result
  }

  /**
   * Counts accepted messages since process startup.
   *
   * @param level severity to count
   */
  def count(level: Level): Long = Option(counts.get(level)).fold(0L)(_.get)

  /**
   * Captures the bounded history of accepted warnings and errors.
   *
   * @return recent entries, oldest first
   */
  def recentProblems: Seq[Entry] = recent.synchronized(recent.toArray(Array.empty[Entry]).toSeq)

  /** Applies the threshold, updates counters and recent problems, then writes to each sink. */
  private def log(level: Level, message: String, cause: Throwable): Unit =
    if (level.ordinal >= threshold.ordinal) {
      val text = if (cause == null) message else {
        val trace = new StringWriter
        cause.printStackTrace(new PrintWriter(trace))
        s"$message\n$trace".stripTrailing
      }
      val entry = Entry(Instant.now(), level, text)
      counts.computeIfAbsent(level, _ => new AtomicLong()).incrementAndGet()
      if (level.ordinal >= Level.Warn.ordinal) recent.synchronized {
        recent.addLast(entry)
        if (recent.size > RecentProblems) recent.removeFirst()
      }
      if (sinks.isEmpty) System.err.println(entry)
      else sinks.forEach { sink =>
        // A sink that fails (a client gone) cannot log its own failure: it goes to stderr, the entry with it.
        try sink(entry)
        catch { case scala.util.control.NonFatal(e) => System.err.println(s"$entry\n(log sink failed: $e)") }
      }
    }
}
