package scalaanalyzer.platform

import com.intellij.openapi.progress.ProgressIndicator

import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/** Coordinates fair read/write actions, write intent and cancellation of active queries. */
private[scalaanalyzer] object HostLock {
  private val lock = new java.util.concurrent.locks.ReentrantReadWriteLock(/* fair = */ true)
  private val queries = java.util.concurrent.ConcurrentHashMap.newKeySet[com.intellij.openapi.progress.ProgressIndicator]()
  // Covers readers registering after cancellation, including while a writer waits for write intent.
  private val pendingWrites = new java.util.concurrent.atomic.AtomicInteger()

  private val intent = new java.util.concurrent.locks.ReentrantLock(true)
  private val writeListeners = new java.util.concurrent.CopyOnWriteArrayList[com.intellij.openapi.application.WriteActionListener]()
  private val readListeners = new java.util.concurrent.CopyOnWriteArrayList[com.intellij.openapi.application.ReadActionListener]()
  private val intentListeners = new java.util.concurrent.CopyOnWriteArrayList[com.intellij.openapi.application.WriteIntentReadActionListener]()

  /** Whether the current thread holds a host read lock. */
  def isReading: Boolean = lock.getReadHoldCount > 0

  /** Whether the current thread holds the exclusive edit lock. */
  def isWriting: Boolean = lock.isWriteLockedByCurrentThread

  /** Whether the current thread holds the serialized write-intent lock. */
  def hasWriteIntent: Boolean = intent.isHeldByCurrentThread

  /** Registers a write-action listener and removes it when `parent` is disposed. */
  def addWriteListener(listener: com.intellij.openapi.application.WriteActionListener,
                       parent: com.intellij.openapi.Disposable): Unit = {
    writeListeners.add(listener)
    com.intellij.openapi.util.Disposer.register(parent, (() => {
      writeListeners.remove(listener)
      ()
    }): com.intellij.openapi.Disposable)
  }

  /** Registers a read-action listener and removes it when `parent` is disposed. */
  def addReadListener(listener: com.intellij.openapi.application.ReadActionListener,
                      parent: com.intellij.openapi.Disposable): Unit = {
    readListeners.add(listener)
    com.intellij.openapi.util.Disposer.register(parent, (() => {
      readListeners.remove(listener)
      ()
    }): com.intellij.openapi.Disposable)
  }

  /** Registers a write-intent listener and removes it when `parent` is disposed. */
  def addIntentListener(listener: com.intellij.openapi.application.WriteIntentReadActionListener,
                        parent: com.intellij.openapi.Disposable): Unit = {
    intentListeners.add(listener)
    com.intellij.openapi.util.Disposer.register(parent, (() => {
      intentListeners.remove(listener)
      ()
    }): com.intellij.openapi.Disposable)
  }

  /** Serializes potential writers reentrantly, rejecting upgrades from a read without existing intent. */
  def writeIntent[T](body: => T): T = {
    if (isReading && !hasWriteIntent) throw new IllegalStateException("write intent cannot start inside a read")
    if (hasWriteIntent) body
    else {
      val listeners = intentListeners.asScala.toVector
      val action = HostLock.getClass
      listeners.foreach(_.beforeWriteIntentReadActionStart(action))
      intent.lock()
      try {
        listeners.foreach(_.writeIntentReadActionStarted(action))
        try body
        finally listeners.foreach(_.writeIntentReadActionFinished(action))
      } finally {
        intent.unlock()
        listeners.foreach(_.afterWriteIntentReadActionFinished(action))
      }
    }
  }

  /** Cancels queries already registered with the host's progress manager. */
  def cancelQueries(): Unit = queries.forEach(_.cancel())

  /**
   * Cancellation shared by the queries of one client request: cancelling it cancels the query running for the
   * request and every query the request starts afterwards.
   */
  final class RequestCancellation {
    @volatile private var cancelled = false
    private val running = ConcurrentHashMap.newKeySet[ProgressIndicator]()

    def cancel(): Unit = {
      cancelled = true
      running.forEach(_.cancel())
    }

    /** Registers before reading the flag, so a concurrent `cancel` either sees the query or is seen by it. */
    private[HostLock] def track(indicator: ProgressIndicator): Unit = {
      running.add(indicator)
      if (cancelled) indicator.cancel()
    }

    private[HostLock] def untrack(indicator: ProgressIndicator): Unit = running.remove(indicator)
  }

  private val requestCancellation = new ThreadLocal[RequestCancellation]

  /** Runs `body` with its queries cancelled by `cancellation`. */
  def cancelledBy[T](cancellation: RequestCancellation)(body: => T): T = {
    val outer = requestCancellation.get()
    requestCancellation.set(cancellation)
    try body
    finally requestCancellation.set(outer)
  }

  /** A read without its own cancellation (the platform's `runReadAction`): the lock only. */
  def readLocked[T](body: => T): T =
    if (isReading || hasWriteIntent) body
    else {
      val listeners = readListeners.asScala.toVector
      val action = HostLock.getClass
      listeners.foreach(_.beforeReadActionStart(action))
      lock.readLock.lock()
      try {
        listeners.foreach(_.readActionStarted(action))
        try body
        finally listeners.foreach(_.readActionFinished(action))
      } finally {
        lock.readLock.unlock()
        listeners.foreach(_.afterReadActionFinished(action))
      }
    }

  /** [[readLocked]] for Java callers. */
  def readLocked[T](body: java.util.concurrent.Callable[T]): T = readLocked(body.call())

  /** Runs a cancellable read, reusing an outer read or write and its cancellation scope. */
  def query[T](body: => T): T =
    if (isReading || isWriting) body
    else {
      val indicator = new com.intellij.openapi.progress.EmptyProgressIndicator
      readLocked {
        com.intellij.openapi.progress.ProgressManager.getInstance.runProcess((() => {
          // runProcess starts the indicator, resetting cancellation; only register it after that reset.
          queries.add(indicator)
          val cancellation = Option(requestCancellation.get())
          cancellation.foreach(_.track(indicator))
          try {
            // An intent owner may query PSI while preparing its own write, without taking a read lock.
            if (!hasWriteIntent && pendingWrites.get() > 0) indicator.cancel()
            indicator.checkCanceled()
            body
          } finally {
            cancellation.foreach(_.untrack(indicator))
            queries.remove(indicator)
          }
        }): com.intellij.openapi.util.Computable[T], indicator)
      }
    }

  /** Cancels active queries and writes exclusively, reusing nested writes and rejecting read-to-write upgrades. */
  def write[T](body: => T): T = {
    // A read lock cannot become a write lock: waiting for it here would wait for this thread's own read forever.
    if (isReading && !isWriting) throw new IllegalStateException("an edit cannot start inside a read")
    if (isWriting) body
    else {
      pendingWrites.incrementAndGet()
      try {
        cancelQueries()
        writeIntent {
          val listeners = writeListeners.asScala.toVector
          val action = HostLock.getClass
          listeners.foreach(_.beforeWriteActionStart(action))
          lock.writeLock.lock()
          try {
            listeners.foreach(_.writeActionStarted(action))
            try body
            finally listeners.foreach(_.writeActionFinished(action))
          } finally {
            lock.writeLock.unlock()
            listeners.foreach(_.afterWriteActionFinished(action))
          }
        }
      } finally pendingWrites.decrementAndGet()
    }
  }
}

/** Runs platform UI-thread callbacks on a process-owned daemon registered as the platform's EDT. */
private[scalaanalyzer] object HostDispatchThread {
  private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable =>
    val thread = new Thread(runnable, "scala-analyzer-dispatch")
    thread.setDaemon(true)
    thread
  }
  executor.submit((() => com.intellij.util.ui.EDT.updateEdt()): Runnable).get()

  /** Whether the caller is running on the host's platform-registered dispatch thread. */
  def isCurrent: Boolean = com.intellij.util.ui.EDT.isCurrentThreadEdt

  /** Queues platform work for the host's dispatch thread. */
  def invokeLater(action: Runnable): Unit = executor.execute(action)

  /** Runs on the dispatch thread and waits for completion, unless already on that thread. */
  def invokeAndWait(action: Runnable): Unit =
    if (isCurrent) action.run() else executor.submit(action).get()

  /** Runs on the dispatch thread and returns the result, rethrowing the operation's failure on the caller. */
  def call[T](body: => T): T =
    if (isCurrent) body
    else
      try executor.submit((() => body): java.util.concurrent.Callable[T]).get()
      catch { case e: java.util.concurrent.ExecutionException => throw e.getCause }

  /** Waits until the work scheduled so far has run. */
  def drain(): Unit = invokeAndWait(() => ())
}
