package scalaanalyzer.build

import com.intellij.lang.annotation.HighlightSeverity

import scalaanalyzer.LineColumn
import scalaanalyzer.platform.HostLog

import java.io.File
import java.lang.reflect.{InvocationHandler, Method, Proxy}
import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.Comparator
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock

import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Compiles current document text with a Scala 3 module's own compiler.
 *
 * Compilation uses the module's classpath and existing class output, stopping before code generation by default.
 * Compiler loaders are isolated from the host's Scala and reused per set of compiler jars. The session must close
 * this instance after use; close waits for in-flight compilations. Scala 2 diagnostics come from the build instead.
 */
final class DocumentCompiler extends AutoCloseable {
  private val loaders = new ConcurrentHashMap[Seq[Path], URLClassLoader]()
  private val lifecycle = new ReentrantReadWriteLock(true)
  private var closed = false

  /**
   * Compiles a document snapshot and collects its diagnostics.
   *
   * Temporary source and output files are removed before returning. Compilation may run concurrently;
   * cached class loaders remain open until this instance is closed.
   *
   * @param spec Scala 3 compiler settings for the source's module
   * @param fileName source filename within the temporary compilation directory
   * @param text current document contents
   * @return compiler diagnostics, including run-level errors at the start of the document
   * @throws UnsupportedOperationException when the module uses Scala 2
   * @throws java.lang.IllegalStateException when this compiler has been closed
   */
  def compile(spec: CompileSpec, fileName: String, text: String): Seq[CompilerDiagnostic] = {
    if (!spec.scalaVersion.startsWith("3."))
      throw new UnsupportedOperationException(
        s"document compilation needs a Scala 3 module (its compiler's Java interface); this one is on Scala ${spec.scalaVersion}")
    val readLock = lifecycle.readLock()
    readLock.lock()
    try {
      if (closed) throw new IllegalStateException("document compiler is closed")
      val loader = loaders.computeIfAbsent(spec.compilerJars,
        jars => new URLClassLoader(jars.map(_.toUri.toURL).toArray, ClassLoader.getPlatformClassLoader))
      val workDir = Files.createTempDirectory("scala-analyzer-document")
      try {
        val source = workDir.resolve(fileName)
        Files.writeString(source, text)
        val output = Files.createDirectory(workDir.resolve("classes"))
        val args =
          Seq("-classpath", (spec.classpath :+ spec.classDirectory).mkString(File.pathSeparator), "-d", output.toString) ++
            highlightingOptions(spec) :+ source.toString
        runCompiler(loader, args)
      } finally {
        Using.resource(Files.walk(workDir))(_.sorted(Comparator.reverseOrder()).forEach(Files.delete))
      }
    } finally {
      readLock.unlock()
    }
  }

  /**
   * Waits for active compilations and closes cached compiler loaders.
   *
   * Closing is idempotent. Every loader is attempted; later close failures are suppressed on the first.
   */
  override def close(): Unit = {
    val writeLock = lifecycle.writeLock()
    writeLock.lock()
    try {
      if (!closed) {
        closed = true
        var failure: Option[Throwable] = None
        loaders.values().asScala.foreach { loader =>
          try loader.close()
          catch {
            case scala.util.control.NonFatal(error) =>
              failure match {
                case Some(first) => first.addSuppressed(error)
                case None => failure = Some(error)
              }
          }
        }
        loaders.clear()
        failure.foreach(error => throw error)
      }
    } finally {
      writeLock.unlock()
    }
  }

  /** Applies the plugin's pre-code-generation stop and the Scala 3.3+ unused-import diagnostic option. */
  private def highlightingOptions(spec: CompileSpec): Seq[String] = {
    val stopAfter =
      if (spec.scalacOptions.exists(_.startsWith("-Ystop-after:"))) Seq.empty else Seq("-Ystop-after:repeatableAnnotations")
    val unusedImports =
      if (atLeast3_3(spec.scalaVersion) && !spec.scalacOptions.exists(o => o.startsWith("-Wunused:") && (o.contains("imports") || o.contains("all"))))
        Seq("-Wunused:imports")
      else Seq.empty
    spec.scalacOptions ++ stopAfter ++ unusedImports
  }

  /** Whether `version` enables the plugin's unused-import check by default. */
  private def atLeast3_3(version: String): Boolean = version.split('.') match {
    case Array("3", minor, _*) => minor.toIntOption.exists(_ >= 3)
    case _ => false
  }

  /** Runs dotc through its Java interface and collects diagnostics without linking the host to that Scala version. */
  private def runCompiler(loader: ClassLoader, args: Seq[String]): Seq[CompilerDiagnostic] = {
    /** Calls a version-specific compiler-interface method without linking to that compiler version. */
    def call(target: AnyRef, interface: Class[?], method: String): AnyRef = interface.getMethod(method).invoke(target)

    val reporterInterface = loader.loadClass("dotty.tools.dotc.interfaces.SimpleReporter")
    val callbackInterface = loader.loadClass("dotty.tools.dotc.interfaces.CompilerCallback")
    val diagnosticInterface = loader.loadClass("dotty.tools.dotc.interfaces.Diagnostic")
    val positionInterface = loader.loadClass("dotty.tools.dotc.interfaces.SourcePosition")
    val reported = mutable.ArrayBuffer.empty[CompilerDiagnostic]
    val reporter: InvocationHandler = (proxy: AnyRef, method: Method, args: Array[AnyRef]) => method.getName match {
      case "report" =>
        val diagnostic = args(0)
        val position = call(diagnostic, diagnosticInterface, "position").asInstanceOf[java.util.Optional[AnyRef]]
        val level = severity(call(diagnostic, diagnosticInterface, "level").asInstanceOf[Int])
        val message = call(diagnostic, diagnosticInterface, "message").asInstanceOf[String]
        // A problem without a position is about the run, not the document. An error (e.g. a bad option) explains why
        // the document has no other problems, so it is reported at the start of the file; anything else (the
        // compiler's "N errors found" summary is a warning) is only logged.
        /** Reads compiler coordinates, locating a positionless run error at the document start. */
        def at(line: String, column: String): LineColumn =
          if (position.isEmpty) LineColumn(0, 0)
          else LineColumn(call(position.get, positionInterface, line).asInstanceOf[Int], call(position.get, positionInterface, column).asInstanceOf[Int])
        if (position.isPresent || level == HighlightSeverity.ERROR)
          reported += CompilerDiagnostic(level, at("startLine", "startColumn"), at("endLine", "endColumn"), message)
        else HostLog.debug(s"compiler: $message")
        null
      case "hashCode" => Integer.valueOf(System.identityHashCode(proxy))
      case "equals" => java.lang.Boolean.valueOf(proxy eq args(0))
      case "toString" => "HostDocumentReporter"
    }

    // The compiler's entry point is the object dotty.tools.dotc.Main, whose instance lives in its module class.
    val mainModule = loader.loadClass("dotty.tools.dotc.Main$")
    val process = mainModule.getMethod("process", classOf[Array[String]], reporterInterface, callbackInterface)
    val thread = Thread.currentThread
    val previousLoader = thread.getContextClassLoader
    thread.setContextClassLoader(loader)
    try {
      val compiler = mainModule.getField("MODULE$").get(null)
      val simpleReporter = Proxy.newProxyInstance(loader, Array(reporterInterface), reporter)
      process.invoke(compiler, args.toArray, simpleReporter, null)
    }
    finally thread.setContextClassLoader(previousLoader)
    reported.toSeq
  }

  /** Maps the compiler-interface severity constants to the platform's diagnostic levels. */
  private def severity(level: Int): HighlightSeverity = level match {
    case 2 => HighlightSeverity.ERROR
    case 1 => HighlightSeverity.WARNING
    case _ => HighlightSeverity.INFORMATION
  }
}
