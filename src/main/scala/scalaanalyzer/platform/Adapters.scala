package scalaanalyzer.platform

import com.intellij.codeInsight.InferredAnnotationsManager
import com.intellij.openapi.diagnostic.DefaultLogger
import com.intellij.openapi.vfs.{VirtualFile, VirtualFileSet}
import com.intellij.psi.{PsiAnnotation, PsiModifierListOwner}
import com.intellij.util.Processor

import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

import scala.jdk.CollectionConverters.*

/**
 * Records the host's adapter surface and unsupported calls.
 *
 * Answered calls are counted only when [[countsAnswered]] is enabled. Unsupported calls and logged errors
 * are always recorded; counts accumulate for the lifetime of the process.
 */
object CallLog {
  private val counts = new ConcurrentHashMap[String, AtomicInteger]()

  /**
   * Enables counting of answered calls for adapter-surface tests.
   *
   * Disabled in normal server use to avoid accounting on every platform call.
   */
  @volatile var countsAnswered = false

  /**
   * Records one adapter call.
   *
   * @param name diagnostic name used to aggregate calls
   */
  def hit(name: String): Unit = counts.computeIfAbsent(name, _ => new AtomicInteger()).incrementAndGet()

  /**
   * Records an answered call when counting is enabled.
   *
   * @param name diagnostic name, evaluated only when counting is enabled
   */
  def answered(name: => String): Unit = if (countsAnswered) hit(name)

  /**
   * Records and rejects a call outside the host's adapter surface.
   *
   * @tparam T result type expected by the unsupported caller
   * @param name diagnostic name of the unsupported call
   * @return never returns normally
   * @throws UnsupportedOperationException for every call
   */
  def unsupported[T](name: String): T = {
    hit("UNSUPPORTED " + name)
    throw new UnsupportedOperationException("host adapter does not support: " + name)
  }

  /**
   * Captures adapter-call counts for status output and tests.
   *
   * Counts may continue to change while the snapshot is collected.
   *
   * @return diagnostic names and counts, sorted by name
   */
  def snapshot: Seq[(String, Int)] = counts.asScala.toSeq.map((k, v) => (k, v.get)).sortBy(_._1)
}

/**
 * Routes platform and plugin warnings and errors to [[HostLog]].
 *
 * As in the IDE, `LOG.error` reports and execution continues. Each logged error is recorded
 * in [[CallLog]], so a run that logs one shows it in the adapter surface. Unsupported adapter calls are recorded
 * before they throw, so a caller that catches and logs one does not hide it. Their info and debug messages are not
 * kept (the IDE's default log level).
 *
 * @param category platform logger category included in diagnostics
 */
final class HostLogger(category: String) extends DefaultLogger(category) {
  /**
   * Records the error in [[CallLog]] and [[HostLog]] without throwing.
   *
   * @param message error text
   * @param t failure to render, or null when absent
   * @param details platform-supplied extra details; not logged
   */
  override def error(message: String, t: Throwable, details: String*): Unit = {
    CallLog.hit(s"LOGGED ERROR $category")
    HostLog.error(s"[$category] $message", t)
  }

  /**
   * Writes the warning to [[HostLog]].
   *
   * @param message warning text
   * @param t failure to render, or null when absent
   */
  override def warn(message: String, t: Throwable): Unit = HostLog.warn(s"[$category] $message", t)
}

// What an answer table gives for a call it has no case for. One lookup (applyOrElse) instead of two (isDefinedAt,
// then apply): tables answer the platform's most frequent calls.
private val NotAnswered = new AnyRef
private val Unanswered: Any => AnyRef = _ => NotAnswered

/**
 * Creates an interface proxy backed by explicit answers.
 *
 * Identity methods use the proxy itself, declared answers take precedence over interface
 * defaults, and unhandled calls are recorded by [[CallLog]] before throwing `UnsupportedOperationException`.
 *
 * @tparam T interface implemented by the proxy
 * @param iface interface class to implement
 * @param label diagnostic prefix for calls and the proxy's string representation
 * @param answer supported method names and arguments, returning boxed results or null
 * @return a proxy implementing `iface`
 */
def tableProxy[T](iface: Class[T], label: String)(answer: PartialFunction[(String, Array[AnyRef]), AnyRef]): T = {
  val handler: InvocationHandler = (proxy: AnyRef, m: Method, args0: Array[AnyRef]) => {
    val args = if (args0 == null) Array.empty[AnyRef] else args0

    m.getName match {
      case "hashCode" if args.isEmpty => Integer.valueOf(System.identityHashCode(proxy))
      case "equals" if args.length == 1 => java.lang.Boolean.valueOf(proxy eq args(0))
      case "toString" if args.isEmpty => s"Host$label"
      case name =>
        val answered = answer.applyOrElse((name, args), Unanswered)

        if (answered ne NotAnswered) {
          CallLog.answered(s"$label.$name")
          answered
        } else if (m.isDefault) {
          CallLog.answered(s"$label.$name (default)")
          InvocationHandler.invokeDefault(proxy, m, args*)
        } else CallLog.unsupported(s"$label.$name")
    }
  }

  iface.cast(Proxy.newProxyInstance(iface.getClassLoader, Array[Class[?]](iface), handler))
}

/**
 * Creates the dispatch callback used by generated `Host_*` subclasses.
 *
 * Unanswered abstract methods are recorded and rejected; see `tools/Gen.java`.
 *
 * @param answer supported method names and arguments, returning boxed results or null
 * @return the generated adapter's dispatch callback
 */
def dispatch(answer: PartialFunction[(String, Array[AnyRef]), AnyRef]): Dispatch =
  (method: String, args0: Array[AnyRef]) => {
    val args = if (args0 == null) Array.empty[AnyRef] else args0
    val answered = answer.applyOrElse((method, args), Unanswered)

    if (answered ne NotAnswered) {
      CallLog.answered(method)
      answered
    } else CallLog.unsupported(method)
  }

/**
 * The host has no annotation inference engine; declared annotations remain in PSI.
 *
 * Every lookup is recorded in [[CallLog]] and finds no inferred annotation.
 */
private[scalaanalyzer] final class HostInferredAnnotationsManager extends InferredAnnotationsManager {
  /**
   * Finds no inferred annotations.
   *
   * @param owner annotated element; not inspected
   * @return an empty array
   */
  override def findInferredAnnotations(owner: PsiModifierListOwner): Array[PsiAnnotation] = {
    CallLog.hit("InferredAnnotationsManager.findInferredAnnotations")
    PsiAnnotation.EMPTY_ARRAY
  }

  /**
   * Finds no inferred annotation.
   *
   * @param owner annotated element; not inspected
   * @param name annotation name; not inspected
   * @return null
   */
  override def findInferredAnnotation(owner: PsiModifierListOwner, name: String): PsiAnnotation = {
    CallLog.hit("InferredAnnotationsManager.findInferredAnnotation")
    null
  }
}

/**
 * Supplies explicitly registered services for one application or project.
 *
 * Unregistered services fail loudly. Instances are created on first request.
 * Concurrent first requests may invoke a factory more than once; all callers receive the instance published first.
 * Factories can request other registered services, so creation cannot use a nested `computeIfAbsent`.
 *
 * @param label component-manager name included in diagnostics
 */
final class ServiceTable(label: String) {
  private val factories = new ConcurrentHashMap[Class[?], () => AnyRef]()
  private val instances = new ConcurrentHashMap[Class[?], AnyRef]()
  /** Services the host deliberately answers with null (the caller tolerates it). */
  private val nulls = ConcurrentHashMap.newKeySet[Class[?]]()

  /**
   * Registers a service factory for lazy creation.
   *
   * Register before requesting the service; an already published instance is retained.
   * Factories must tolerate concurrent first requests constructing more than one candidate.
   *
   * @tparam T service type
   * @param cls service class used as the lookup key
   * @param make factory expression evaluated when an instance is needed
   */
  def register[T <: AnyRef](cls: Class[T])(make: => T): Unit = factories.put(cls, () => make)

  /**
   * Registers an intentionally absent service whose callers tolerate null.
   *
   * @param cls service class whose lookups should return null
   */
  def registerNull(cls: Class[?]): Unit = nulls.add(cls)

  /**
   * Returns or lazily creates a registered service.
   *
   * Explicit null registrations take precedence over factories and cached instances.
   *
   * @param cls registered service class
   * @return the published instance, or null for an explicit null registration
   * @throws UnsupportedOperationException when the service is unregistered
   */
  def get(cls: Class[?]): AnyRef =
    if (nulls.contains(cls)) {
      CallLog.answered(s"$label.service(null) ${cls.getName}")
      null
    } else {
      val make = factories.get(cls)
      if (make == null) CallLog.unsupported(s"$label.getService ${cls.getName}")
      else {
        CallLog.answered(s"$label.service ${cls.getName}")
        // Not computeIfAbsent: service constructors fetch other services, and ConcurrentHashMap forbids
        // nested updates. Concurrent first requests may build twice; one instance wins.
        val existing = instances.get(cls)
        if (existing != null) existing
        else {
          val created = make()
          val raced = instances.putIfAbsent(cls, created)
          if (raced != null) raced else created
        }
      }
    }
}

/**
 * A virtual-file set that retains the files directly.
 *
 * The platform's compact set looks up the VFS manager even for files without ids; the host keeps files directly.
 * Freezing replaces the backing set with an unmodifiable view, so later mutations throw.
 *
 * @param files initial backing set; replaced by an unmodifiable view when the set is frozen
 */
private[scalaanalyzer] final class HostVirtualFileSet(private var files: java.util.Set[VirtualFile])
    extends java.util.AbstractSet[VirtualFile] with VirtualFileSet {

  /** Creates an initially empty set that retains virtual-file identities directly. */
  def this() = this(new java.util.LinkedHashSet)

  override def iterator(): java.util.Iterator[VirtualFile] = files.iterator()
  override def size(): Int = files.size
  override def contains(o: Object): Boolean = files.contains(o)
  override def add(file: VirtualFile): Boolean = files.add(file)
  override def remove(o: Object): Boolean = files.remove(o)

  /** Freezes the set and returns this same view. */
  override def freezed(): java.util.Set[VirtualFile] = {
    freeze()
    this
  }

  /** Prevents further mutations by replacing the backing set with an unmodifiable view. */
  override def freeze(): Unit = files = java.util.Collections.unmodifiableSet(files)
  override def process(processor: Processor[? >: VirtualFile]): Boolean =
    files.asScala.forall(processor.process)
}
