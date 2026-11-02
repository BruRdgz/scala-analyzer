package scalaanalyzer.build

import com.intellij.lang.annotation.HighlightSeverity

import scalaanalyzer.{LineColumn, ModuleSpec}

import java.nio.file.Path

/**
 * Compiler settings for one imported Scala module.
 *
 * @param scalaVersion Scala version reported by the build
 * @param compilerJars jars used to load the module's compiler in isolation
 * @param classpath full compile classpath, including dependent modules' output directories
 * @param classDirectory module's existing class output, added to the document compiler's classpath
 * @param scalacOptions compiler options reported by the build
 */
final case class CompileSpec(
  scalaVersion: String,
  compilerJars: Seq[Path],
  classpath: Seq[Path],
  classDirectory: Path,
  scalacOptions: Seq[String],
)

/**
 * A Scala build target with its sources and compiler settings.
 *
 * @param spec host module identity, dependencies and PSI settings
 * @param sources Scala and Java source paths owned by the target
 * @param compile settings for compiling current document text
 */
final case class ImportedModule(spec: ModuleSpec, sources: Seq[Path], compile: CompileSpec)

/**
 * A compiler problem in a source file.
 *
 * @param severity diagnostic level mapped to platform highlighting
 * @param start zero-based start line and UTF-16 column
 * @param end exclusive end line and UTF-16 column
 * @param message compiler diagnostic text
 */
final case class CompilerDiagnostic(severity: HighlightSeverity, start: LineColumn, end: LineColumn, message: String)

/**
 * The outcome of a build-server compile request.
 *
 * @param succeeded whether BSP reported a successful compile
 * @param diagnostics latest published compiler problems grouped by source path
 */
final case class CompileResult(succeeded: Boolean, diagnostics: Map[Path, Seq[CompilerDiagnostic]])
