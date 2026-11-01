package scalapsihost.platform

import com.intellij.openapi.project.Project
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.impl.file.PsiPackageImpl
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.{PsiClass, PsiClassOwner, PsiElementFinder, PsiJavaFile, PsiManager, PsiPackage}
import com.intellij.psi.impl.PsiManagerEx
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.lang.psi.stubs.index.{ScPackageObjectFqnIndex, ScPackagingFqnIndex, ScalaIndexKeys}

import scalapsihost.ModuleModel

import scala.jdk.CollectionConverters.*

/**
 * Resolves classes and packages for the platform's Java PSI facade.
 *
 * Scala classes come from [[SymbolIndex]]. Java binaries are resolved by path on demand to avoid indexing
 * the entire JDK; packages combine class-root directories with Scala and Java source declarations.
 * Callers must hold the host's PSI read or write access.
 *
 * @param project project owning the PSI and stub indexes
 * @param files mounted class roots used for binary and package lookup
 * @param modules registry of project sources and their owning modules
 */
final class LibraryClassFinder(project: Project, files: LibraryFileSystem, modules: ModuleModel) extends PsiElementFinder {
  /** Obtains the project manager after bootstrap has registered its services. */
  private def psiManager: PsiManager = PsiManager.getInstance(project)

  /**
   * Finds the first visible class with a qualified name.
   *
   * @param qualifiedName dotted class name, including nested class names
   * @param scope eligible source and binary files
   * @return the first match in index/root order, or null if absent
   */
  override def findClass(qualifiedName: String, scope: GlobalSearchScope): PsiClass =
    findClasses(qualifiedName, scope).headOption.orNull

  /**
   * Finds indexed source classes and Java binaries with a qualified name.
   *
   * @param qualifiedName dotted class name, including nested class names
   * @param scope eligible source and binary files
   * @return distinct matches, with indexed classes before binary-path matches
   */
  override def findClasses(qualifiedName: String, scope: GlobalSearchScope): Array[PsiClass] = {
    val indexed = StubIndex.getElements(JavaStubIndexKeys.CLASS_FQN, qualifiedName, project, scope, classOf[PsiClass]).asScala.toSeq
    (indexed ++ javaClassesByPath(qualifiedName, scope)).distinct.toArray
  }

  /** Reads Java sources from the live module registry within the requested scope. */
  private def javaSources(scope: GlobalSearchScope): Seq[PsiJavaFile] =
    modules.all.flatMap(_.sources.asScala).filter(scope.contains).flatMap { source =>
      ProgressManager.checkCanceled()
      Option(psiManager.findFile(source)).collect { case file: PsiJavaFile => file }
    }

  /** `java.util.Map.Entry`: directories `java/util`, class file `Map.class`, then inner class `Entry`. */
  private def javaClassesByPath(qualifiedName: String, scope: GlobalSearchScope): Seq[PsiClass] = {
    val segments = qualifiedName.split('.').toList
    for {
      root <- files.classRoots
      case (dir, topLevel :: innerNames) <- packageDirectories(root, segments)
      file <- Option(dir.findChild(topLevel + ".class")) if scope.contains(file)
      javaFile <- Option(psiManager.findFile(file)).collect { case f: PsiJavaFile => f }
      top <- javaFile.getClasses.find(_.getName == topLevel)
      cls <- innerNames.foldLeft(Option(top))((outer, name) => outer.flatMap(o => Option(o.findInnerClassByName(name, false))))
    } yield cls
  }

  /** Every split of `segments` into an existing package directory under `root` and a non-empty class path. */
  private def packageDirectories(root: VirtualFile, segments: List[String]): List[(VirtualFile, List[String])] =
    segments match {
      case Nil => Nil
      case head :: tail =>
        val here = List(root -> segments)
        Option(root.findChild(head)).filter(_.isDirectory) match {
          case Some(sub) => here ++ packageDirectories(sub, tail)
          case None => here
        }
    }

  /** Resolves a dotted package name to matching directories in all mounted class roots. */
  private def directoriesOf(packageName: String): Seq[VirtualFile] = {
    val path = packageName.replace('.', '/')
    files.classRoots.flatMap(root => if (path.isEmpty) Some(root) else Option(root.findFileByRelativePath(path)).filter(_.isDirectory))
  }

  /**
   * Finds a directory-backed or source-declared package.
   *
   * @param qualifiedName dotted package name, or empty for the root package
   * @return the package, or null if no directory or source declaration backs it
   */
  override def findPackage(qualifiedName: String): PsiPackage =
    if (directoriesOf(qualifiedName).isEmpty && !isDeclaredPackage(qualifiedName)) null
    else new PsiPackageImpl(psiManager.asInstanceOf[PsiManagerEx], qualifiedName)

  /** Detects source-declared packages so synthetic packages do not expose subpackages from every module. */
  private def isDeclaredPackage(qualifiedName: String): Boolean = {
    val everywhere = GlobalSearchScope.allScope(project)
    ScPackagingFqnIndex.instance.hasElement(qualifiedName, project, everywhere, classOf[ScPackaging]) ||
      ScPackageObjectFqnIndex.instance.hasElement(qualifiedName, project, everywhere, classOf[PsiClass]) ||
      javaSources(everywhere).exists { file =>
        val name = file.getPackageName
        name == qualifiedName || qualifiedName.isEmpty || name.startsWith(qualifiedName + ".")
      }
  }

  /**
   * Lists visible directory-backed and source-declared subpackages.
   *
   * @param psiPackage parent package
   * @param scope eligible source and binary files
   * @return distinct immediate subpackages sorted by name
   */
  override def getSubPackages(psiPackage: PsiPackage, scope: GlobalSearchScope): Array[PsiPackage] = {
    val packageName = psiPackage.getQualifiedName
    val prefix = if (packageName.isEmpty) "" else packageName + "."
    val directories = directoriesOf(packageName).flatMap(_.getChildren)
      .filter(d => d.isDirectory && scope.contains(d)).map(_.getName)
    val javaPackages = javaSources(scope).map(_.getPackageName)
      .filter(name => name.startsWith(prefix) && name != packageName)
      .map(_.stripPrefix(prefix).takeWhile(_ != '.')).filter(_.nonEmpty)
    (directories ++ declaredSubPackages(packageName, prefix, scope) ++ javaPackages).distinct.sorted
      .map(name => new PsiPackageImpl(psiManager.asInstanceOf[PsiManagerEx], prefix + name): PsiPackage).toArray
  }

  /** Finds immediate subpackage names declared by nested packagings and package objects in scope. */
  private def declaredSubPackages(packageName: String, prefix: String, scope: GlobalSearchScope): Seq[String] = {
    // A packaging is indexed under its own name only: the root package's subpackages are those of every name.
    val packageNames =
      if (packageName.isEmpty) StubIndex.getInstance.getAllKeys(ScalaIndexKeys.PACKAGE_FQN_KEY, project).asScala.toSeq.map(_.toString)
      else Seq(packageName)
    packageNames.flatMap(ScPackagingFqnIndex.instance.getElements(_, project, scope).asScala).flatMap { packaging =>
      val name = packaging.fullPackageName
      if (name == packageName) packaging.immediateTypeDefinitions.collect { case o: ScObject if o.isPackageObjectNonLegacy => o.name }
      else if (name.startsWith(prefix)) Seq(name.stripPrefix(prefix).takeWhile(_ != '.'))
      else Seq.empty
    }
  }

  /**
   * Visits source files by declared package, including packages without backing directories.
   *
   * Processing stops when the consumer returns false; a source file is visited at most once.
   *
   * @param psiPackage package whose files should be visited
   * @param scope eligible source files
   * @param includeSubpackages whether to include descendant packages
   * @param consumer callback receiving each matching virtual file
   */
  override def processPackageFiles(psiPackage: PsiPackage, scope: GlobalSearchScope, includeSubpackages: Boolean,
                                   consumer: com.intellij.util.Processor[? >: VirtualFile]): Boolean = {
    val name = psiPackage.getQualifiedName
    val prefix = if (name.isEmpty) "" else name + "."
    val names = if (includeSubpackages)
      StubIndex.getInstance.getAllKeys(ScalaIndexKeys.PACKAGE_FQN_KEY, project).asScala.toSeq
        .map(_.toString).filter(n => n == name || n.startsWith(prefix))
    else Seq(name)
    val sources = names.flatMap(ScPackagingFqnIndex.instance.getElements(_, project, scope).asScala)
      .map(_.getContainingFile.getVirtualFile).distinct
    val javaFiles = javaSources(scope).filter { file =>
      file.getPackageName == name || includeSubpackages && (name.isEmpty || file.getPackageName.startsWith(prefix))
    }.map(_.getVirtualFile)
    (sources ++ javaFiles).distinct.forall(consumer.process)
  }

  /**
   * Lists source and binary classes directly inside a visible package.
   *
   * @param psiPackage package whose directories should be searched
   * @param scope eligible source and class files
   * @return distinct classes declared in the package
   */
  override def getClasses(psiPackage: PsiPackage, scope: GlobalSearchScope): Array[PsiClass] = {
    val sources = javaSources(scope).filter(_.getPackageName == psiPackage.getQualifiedName).flatMap(_.getClasses)
    val binaries = directoriesOf(psiPackage.getQualifiedName).flatMap(_.getChildren)
      .filter(f => !f.isDirectory && f.getName.endsWith(".class") && scope.contains(f))
      .flatMap(f => Option(psiManager.findFile(f)))
      .flatMap {
        case owner: PsiClassOwner => owner.getClasses.toSeq
        case _ => Seq.empty
      }
    (sources ++ binaries).distinct.toArray
  }
}
