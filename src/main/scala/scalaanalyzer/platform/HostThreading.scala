package scalaanalyzer.platform

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{ReadActionListener, WriteActionListener, WriteIntentReadActionListener}
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProgressIndicator, ProgressManager}
import com.intellij.openapi.util.{Computable, Disposer}
import com.intellij.util.ui.EDT

import java.util.concurrent.{Callable, ConcurrentHashMap, CopyOnWriteArrayList, ExecutionException, Executors}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.{ReentrantLock, ReentrantReadWriteLock}

import scala.jdk.CollectionConverters.*

/**
 * Coordinates fair read/write actions, write intent and cancellation of active queries.
 *
 * A writer takes write intent first and the exclusive write lock second (see [[write]]). A thread holding write
 * intent or the write lock reads without taking the read lock, so [[isReading]] is false for it.
 */
private[scalaanalyzer] object HostLock {
  private val lock = new ReentrantReadWriteLock(/* fair = */ true)
  /** Progress indicators of the queries currently running through [[query]]. */
  private val queries = ConcurrentHashMap.newKeySet[ProgressIndicator]()
  // Covers readers registering after cancellation, including while a writer waits for write intent.
  private val pendingWrites = new AtomicInteger()

  private val intent = new ReentrantLock(true)
  private val writeListeners = new CopyOnWriteArrayList[WriteActionListener]()
  private val readListeners = new CopyOnWriteArrayList[ReadActionListener]()
  private val intentListeners = new CopyOnWriteArrayList[WriteIntentReadActionListener]()

  /**
   * Whether the current thread holds a host read lock.
   *
   * A thread that only holds write intent or the write lock reads without the read lock and reports false.
   */
  def isReading: Boolean = lock.getReadHoldCount > 0

  /** Whether the current thread holds the exclusive edit lock. */
  def isWriting: Boolean = lock.isWriteLockedByCurrentThread

  /** Whether the current thread holds the serialized write-intent lock. */
  def hasWriteIntent: Boolean = intent.isHeldByCurrentThread

  /**
   * Registers a write-action listener and removes it when `parent` is disposed.
   *
   * @param listener callback notified around each outermost write action
   * @param parent disposable whose disposal unregisters the listener
   */
  def addWriteListener(listener: WriteActionListener, parent: Disposable): Unit = {
    writeListeners.add(listener)
    Disposer.register(parent, (() => {
      writeListeners.remove(listener)
      ()
    }): Disposable)
  }

  /**
   * Registers a read-action listener and removes it when `parent` is disposed.
   *
   * @param listener callback notified around each read action that takes the read lock
   * @param parent disposable whose disposal unregisters the listener
   */
  def addReadListener(listener: ReadActionListener, parent: Disposable): Unit = {
    readListeners.add(listener)
    Disposer.register(parent, (() => {
      readListeners.remove(listener)
      ()
    }): Disposable)
  }

  /**
   * Registers a write-intent listener and removes it when `parent` is disposed.
   *
   * @param listener callback notified around each outermost write-intent action
   * @param parent disposable whose disposal unregisters the listener
   */
  def addIntentListener(listener: WriteIntentReadActionListener, parent: Disposable): Unit = {
    intentListeners.add(listener)
    Disposer.register(parent, (() => {
      intentListeners.remove(listener)
      ()
    }): Disposable)
  }

  /**
   * Serializes potential writers reentrantly, rejecting upgrades from a read without existing intent.
   *
   * A thread that already holds write intent runs `body` directly and notifies no listeners. Otherwise the intent
   * listeners are notified around `body`, which runs while holding the intent lock; no read or write lock is taken.
   *
   * @param body action to run while holding write intent
   * @return the result of `body`
   * @throws IllegalStateException when the thread holds a read lock but not write intent
   */
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

  /**
   * Runs a query on the host's dispatch thread, for the platform code that insists on the event thread.
   *
   * The dispatch thread takes its own read through [[query]], so work queued there before (the platform's `invokeLater`
   * tasks, some of which write) runs first, and a write cancels the query as it cancels any other. The caller's
   * [[RequestCancellation]] cancels the query too, also while it still waits in the dispatch thread's queue.
   *
   * The caller waits for the dispatch thread and must hold neither the read lock, the write lock nor write intent: a
   * dispatch-thread task that writes waits for the caller's read or write intent to end, and the dispatch thread's own
   * read would wait for the caller's write, while the caller waits for the dispatch thread.
   *
   * @param body action that reads PSI
   * @return the result of `body`
   * @throws IllegalStateException when the thread holds the read lock, the write lock or write intent
   * @throws com.intellij.openapi.progress.ProcessCanceledException when a write or the caller's request cancels the
   *                                                                query
   */
  def queryOnDispatchThread[T](body: => T): T = {
    if (isReading || isWriting || hasWriteIntent)
      throw new IllegalStateException("the dispatch thread cannot be awaited inside a read, an edit or write intent")
    // The request's cancellation is a thread-local of the caller, which the dispatch thread does not see on its own.
    val cancellation = Option(requestCancellation.get())

    HostDispatchThread.call(cancellation.fold(query(body))(cancelledBy(_)(query(body))))
  }

  /** Cancels every query currently running through [[query]]. */
  def cancelQueries(): Unit = queries.forEach(_.cancel())

  /**
   * Cancellation shared by the queries of one client request: cancelling it cancels the query running for the
   * request and every query the request starts afterwards.
   */
  final class RequestCancellation {
    @volatile private var cancelled = false
    private val running = ConcurrentHashMap.newKeySet[ProgressIndicator]()

    /** Marks the request cancelled and cancels its running queries; may be called from any thread. */
    def cancel(): Unit = {
      cancelled = true
      running.forEach(_.cancel())
    }

    /**
     * Registers a running query's indicator and cancels it at once if the request is already cancelled.
     *
     * Registers before reading the flag, so a concurrent `cancel` either sees the query or is seen by it.
     *
     * @param indicator progress indicator of the query that starts running
     */
    private[HostLock] def track(indicator: ProgressIndicator): Unit = {
      running.add(indicator)
      if (cancelled) indicator.cancel()
    }

    /** Whether the request was cancelled. */
    private[HostLock] def isCancelled: Boolean = cancelled

    /**
     * Stops tracking a query's indicator once the query has finished.
     *
     * @param indicator progress indicator previously passed to `track`
     */
    private[HostLock] def untrack(indicator: ProgressIndicator): Unit = running.remove(indicator)
  }

  private val requestCancellation = new ThreadLocal[RequestCancellation]

  /**
   * Whether the request that the current thread runs in [[cancelledBy]] has been cancelled.
   *
   * @return `true` after the request's cancellation, which stays cancelled; `false` for a request still wanted and
   *         for a thread outside [[cancelledBy]]
   */
  def isRequestCancelled: Boolean = Option(requestCancellation.get()).exists(_.isCancelled)

  /**
   * Runs `body` with its queries cancelled by `cancellation`.
   *
   * The cancellation applies to the queries that the current thread starts inside `body`; the previous
   * cancellation of the thread is restored afterwards, also when `body` fails.
   *
   * @param cancellation cancellation shared by the request's queries
   * @param body action whose queries are tracked
   * @return the result of `body`
   */
  def cancelledBy[T](cancellation: RequestCancellation)(body: => T): T = {
    val outer = requestCancellation.get()

    requestCancellation.set(cancellation)
    try body
    finally requestCancellation.set(outer)
  }

  /**
   * A read without its own cancellation (the platform's `runReadAction`): the lock only.
   *
   * A thread that already reads or holds write intent runs `body` directly and notifies no listeners. Otherwise
   * the read listeners are notified around `body`, which runs while holding the read lock.
   *
   * @param body action to run under the read lock
   * @return the result of `body`
   */
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

  /**
   * [[readLocked]] for Java callers.
   *
   * @param body action to run under the read lock
   * @return the result of `body`
   */
  def readLocked[T](body: Callable[T]): T = readLocked(body.call())

  /**
   * Runs a cancellable read, reusing an outer read or write and its cancellation scope.
   *
   * Outside a read or write, `body` runs through [[readLocked]] with a progress indicator that [[write]] cancels,
   * and that the current thread's [[RequestCancellation]] cancels. A cancelled indicator makes the query fail
   * with the platform's cancellation exception from `checkCanceled`.
   *
   * @param body action that reads PSI
   * @return the result of `body`
   */
  def query[T](body: => T): T =
    if (isReading || isWriting) body
    else {
      val indicator = new EmptyProgressIndicator

      readLocked {
        ProgressManager.getInstance.runProcess((() => {
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
        }): Computable[T], indicator)
      }
    }

  /**
   * Cancels active queries and writes exclusively, reusing nested writes and rejecting read-to-write upgrades.
   *
   * A thread that is already writing runs `body` directly. Otherwise the running queries are cancelled, write
   * intent is taken through [[writeIntent]], and the write listeners are notified around `body`, which runs while
   * holding the exclusive write lock.
   *
   * @param body action to run under the write lock
   * @return the result of `body`
   * @throws IllegalStateException when the thread holds a read lock and is not already writing
   */
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

/**
 * Runs platform UI-thread callbacks on a process-owned daemon registered as the platform's EDT.
 *
 * Queued work runs in submission order on a single thread.
 */
private[scalaanalyzer] object HostDispatchThread {
  private val executor = Executors.newSingleThreadExecutor { runnable =>
    val thread = new Thread(runnable, "scala-analyzer-dispatch")
    thread.setDaemon(true)
    thread
  }

  executor.submit((() => EDT.updateEdt()): Runnable).get()

  /** Whether the caller is running on the host's platform-registered dispatch thread. */
  def isCurrent: Boolean = EDT.isCurrentThreadEdt

  /**
   * Queues platform work for the host's dispatch thread.
   *
   * @param action work to run later on the dispatch thread
   */
  def invokeLater(action: Runnable): Unit = executor.execute(action)

  /**
   * Runs on the dispatch thread and waits for completion, unless already on that thread.
   *
   * On the dispatch thread `action` runs immediately. Elsewhere a failure of `action` is rethrown wrapped in an
   * `ExecutionException`, unlike [[call]].
   *
   * @param action work to run on the dispatch thread
   */
  def invokeAndWait(action: Runnable): Unit =
    if (isCurrent) action.run() else executor.submit(action).get()

  /**
   * Runs on the dispatch thread and returns the result, rethrowing the operation's failure on the caller.
   *
   * On the dispatch thread `body` runs immediately.
   *
   * @param body work to run on the dispatch thread
   * @return the result of `body`
   */
  def call[T](body: => T): T =
    if (isCurrent) body
    else
      try executor.submit((() => body): Callable[T]).get()
      catch { case e: ExecutionException => throw e.getCause }

  /** Waits until the work scheduled so far has run. */
  def drain(): Unit = invokeAndWait(() => ())
}
