package scalaanalyzer.platform

import com.intellij.lang.ASTNode
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SimpleModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.{PsiElement, PsiFile, PsiJavaFile, PsiManager}
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.{IndexSink, ObjectStubSerializer, StubElement, StubElementRegistryService, StubIndex, StubIndexKey}
import com.intellij.util.Processor
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile

import scalaanalyzer.LibraryIndexReport

import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}

import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/**
 * Stores stub-index entries for project sources and decompiled Scala libraries.
 *
 * Source entries refer to the file's existing PSI. Library entries are read from stub trees so their syntax
 * trees can remain unloaded. Java library classes are counted during library indexing but resolved elsewhere.
 *
 * Callers protect queries with the host read lock and updates with its write lock: the entry, key and per-file
 * maps must change together. Remove a file's previous entries before indexing it again. Publishing or removing
 * a file advances the modification tracker exposed by `stubIndex`.
 */
final class SymbolIndex {
  /** Pairs cached file identity with PSI so scope checks avoid navigating each element. */
  private final case class IndexedElement(file: VirtualFile, psi: PsiElement)

  private val elements = new ConcurrentHashMap[(String, AnyRef), java.util.List[IndexedElement]]()
  private val keys = new ConcurrentHashMap[String, java.util.Set[AnyRef]]()
  /** Tracks each file's index-name, normalized-key and element postings for removal. */
  private val entriesByFile = new ConcurrentHashMap[PsiFile, Vector[(String, AnyRef, IndexedElement)]]()
  private val tracker = new SimpleModificationTracker

  /** Normalizes CharSequence keys to strings so lookups compare their content. */
  private def normalizeKey(key: AnyRef): AnyRef = key match {
    case text: CharSequence => text.toString
    case other => other
  }

  /**
   * Exposes the platform `StubIndex` adapter over the host's stored entries.
   *
   * Element processing filters by the requested runtime type and, for `GlobalSearchScope`, the containing file.
   * Key enumeration spans the entire index. Containing-file enumeration returns an empty iterator and maximum
   * containing-file counts return zero; neither operation is implemented by this adapter.
   *
   * Processors can stop enumeration by returning `false`. Unsupported adapter methods are recorded and rejected
   * by the host's dispatch table.
   */
  val stubIndex: StubIndex = new Host_StubIndex(dispatch {
    case ("StubIndex.getContainingFilesIterator", args) =>
      CallLog.hit(s"GAP index files ${args(0)} key=${args(1)}")
      java.util.Collections.emptyIterator()
    case ("StubIndex.getAllKeys", Array(indexKey: StubIndexKey[?, ?], _*)) =>
      java.util.List.copyOf(keys.getOrDefault(indexKey.getName, java.util.Set.of()))
    case ("StubIndex.processElements", Array(indexKey: StubIndexKey[?, ?], key, _, scope, _, requiredClass: Class[?], processor: Processor[?])) =>
      /** Tests the cached file against global scopes; other scope values retain the adapter's unfiltered behavior. */
      def inScope(entry: IndexedElement): Boolean = scope match {
        case global: GlobalSearchScope => global.contains(entry.file)
        case _ => true
      }

      val indexName = indexKey.getName
      val postings = elements.get((indexName, normalizeKey(key)))

      if (postings == null) {
        CallLog.hit(s"GAP index $indexName")
        java.lang.Boolean.TRUE
      } else {
        CallLog.hit(s"HIT index $indexName")

        val elementProcessor = processor.asInstanceOf[Processor[PsiElement]]
        val candidates = postings.asScala.iterator
          .filter(entry => requiredClass.isInstance(entry.psi) && inScope(entry))

        java.lang.Boolean.valueOf(candidates.forall(entry => elementProcessor.process(entry.psi)))
      }
    case ("StubIndex.processAllKeys", Array(indexKey: StubIndexKey[?, ?], processor: Processor[?], _*)) =>
      val keyProcessor = processor.asInstanceOf[Processor[AnyRef]]
      val indexKeys = keys.getOrDefault(indexKey.getName, java.util.Set.of())

      java.lang.Boolean.valueOf(indexKeys.asScala.forall(keyProcessor.process))
    case ("StubIndex.getMaxContainingFileCount", _) => Integer.valueOf(0)
    case ("StubIndex.getPerFileElementTypeModificationTracker" | "StubIndex.getStubIndexModificationTracker", _) => tracker
  })

  /**
   * Indexes accepted Scala class and TASTy files beneath a library root.
   *
   * The caller holds the host write lock and supplies a root whose entries have not already been indexed.
   * Library PSI comes from the platform's view providers and plugin decompilers. A Scala file without an available
   * stub tree is reported as refused; Java class files and entries without their own PSI file are counted only.
   *
   * @param project host project used to resolve library virtual files to PSI
   * @param root mounted class-library root to traverse recursively
   * @param includeEntry predicate over root-relative paths such as `scala/Option.class`; applies to class and TASTy files
   * @return `LibraryIndexReport` with traversal outcomes; its occurrence count covers the entire current index
   */
  def indexLibrary(project: Project, root: VirtualFile, includeEntry: String => Boolean): LibraryIndexReport = {
    val psiManager = PsiManager.getInstance(project)
    var classFileCount = 0
    var indexedScalaFileCount = 0
    var javaClassCount = 0
    var nonTopLevelCount = 0
    val refusedEntries = mutable.ArrayBuffer.empty[String]

    /** Visits a directory and records accepted files using paths relative to the mounted root. */
    def visit(directory: VirtualFile, relativeDirectory: String): Unit =
      for (file <- directory.getChildren) {
        val relativePath = relativeDirectory + file.getName

        if (file.isDirectory) visit(file, relativePath + "/")
        else if ((file.getName.endsWith(".class") || file.getName.endsWith(".tasty")) && includeEntry(relativePath)) {
          classFileCount += 1
          psiManager.findFile(file) match {
            case scalaFile: ScalaFile =>
              if (indexLibraryFile(scalaFile)) indexedScalaFileCount += 1
              else refusedEntries += relativePath
            case _: PsiJavaFile => javaClassCount += 1
            case _ => nonTopLevelCount += 1
          }
        }
      }

    visit(root, "")

    val totalOccurrences = entriesByFile.values().asScala.iterator.map(_.size).sum

    LibraryIndexReport(
      classFiles = classFileCount,
      scalaIndexed = indexedScalaFileCount,
      javaClasses = javaClassCount,
      notTopLevel = nonTopLevelCount,
      refused = refusedEntries.toSeq,
      occurrences = totalOccurrences,
    )
  }

  /** Publishes entries from an existing library stub tree, refusing files whose stub tree is unavailable. */
  private def indexLibraryFile(file: ScalaFile): Boolean = file match {
    case stubbed: PsiFileImpl =>
      Option(stubbed.getStubTree) match {
        case Some(tree) =>
          // The first stub belongs to the file, not to an indexed declaration.
          val stubsWithElements = tree.getPlainList.asScala.toSeq.drop(1).map(stub => stub -> stub.getPsi)

          record(file, stubsWithElements)
          true
        case None => false
      }
    case _ => false
  }

  /**
   * Indexes source declarations after checking their stub-to-PSI alignment.
   *
   * The caller holds the host write lock and removes previous entries before reindexing. Stub counts and element
   * types must agree with the AST traversal before any new entries are published. Returns `false` on a mismatch;
   * otherwise publishes entries referring to the file's existing PSI and returns `true`.
   *
   * @param file Scala or Java project-source PSI whose declarations should be indexed
   */
  def indexSourceFile(file: PsiFile): Boolean = {
    val registry = StubElementRegistryService.Companion.getInstance()
    val builder = registry.getStubDescriptor(file.getLanguage).getStubDefinition.getBuilder
    val sourceElements = mutable.ArrayBuffer.empty[PsiElement]

    /** Lists stubs in builder order so they can be compared with the AST traversal. */
    def preorder(stub: StubElement[?]): Seq[StubElement[?]] =
      stub +: stub.getChildrenStubs.asScala.toSeq.flatMap(preorder)

    /** Collects AST-backed PSI using the stub builder's child-skipping and stub-eligibility rules. */
    def collectSourceElements(node: ASTNode): Unit =
      node.getChildren(null).foreach { child =>
        if (!builder.skipChildProcessingWhenBuildingStubs(node, child)) {
          val factory = registry.getStubFactory(child.getElementType)
          if (factory != null && factory.shouldCreateStub(child)) sourceElements += child.getPsi
          collectSourceElements(child)
        }
      }

    val stubs = preorder(builder.buildStubTree(file)).drop(1)

    collectSourceElements(file.getNode)

    val paired = stubs.size == sourceElements.size && stubs.iterator.zip(sourceElements.iterator).forall { (stub, psi) =>
      stub.getElementType == psi.getNode.getElementType
    }

    // No mapping is better than a wrong one: an unpaired file stays out of the index.
    if (paired) record(file, stubs.zip(sourceElements))
    paired
  }

  /** Collects postings with available stub serializers, then publishes the file and advances the tracker. */
  private def record(file: PsiFile, stubsWithElements: Seq[(StubElement[?], PsiElement)]): Unit = {
    val registry = StubElementRegistryService.Companion.getInstance()
    val occurrenceBuilder = Vector.newBuilder[(String, AnyRef, IndexedElement)]
    val virtualFile = file.getViewProvider.getVirtualFile

    for ((stub, psi) <- stubsWithElements; serializer <- Option(registry.getStubSerializer(stub.getElementType))) {
      val element = IndexedElement(virtualFile, psi)
      val sink = new IndexSink {
        override def occurrence[Psi <: PsiElement, K](key: StubIndexKey[K, Psi], value: K): Unit =
          occurrenceBuilder += ((key.getName, normalizeKey(value.asInstanceOf[AnyRef]), element))
      }
      // The element-type registry pairs this serializer with the stub; its erased API loses their shared type.
      serializer.asInstanceOf[ObjectStubSerializer[StubElement[?], ?]].indexStub(stub, sink)
    }

    val fileOccurrences = occurrenceBuilder.result()

    for ((indexName, key, element) <- fileOccurrences) {
      elements.computeIfAbsent((indexName, key), _ => new CopyOnWriteArrayList[IndexedElement]()).add(element)
      keys.computeIfAbsent(indexName, _ => ConcurrentHashMap.newKeySet[AnyRef]()).add(key)
    }
    entriesByFile.put(file, fileOccurrences)
    tracker.incModificationCount()
  }

  /**
   * Removes all postings contributed by an indexed file.
   *
   * The caller holds the host write lock. Keys disappear when their last posting is removed. Removing a file
   * with no recorded contribution has no effect and leaves the modification tracker unchanged.
   *
   * @param file source or library PSI file used when indexing its entries
   */
  def removeFile(file: PsiFile): Unit = {
    val fileOccurrences = entriesByFile.remove(file)

    if (fileOccurrences != null) {
      for ((indexName, key, element) <- fileOccurrences) {
        val postings = elements.get((indexName, key))

        if (postings != null) {
          postings.remove(element)
          if (postings.isEmpty) {
            elements.remove((indexName, key))
            keys.get(indexName).remove(key)
          }
        }
      }
      tracker.incModificationCount()
    }
  }
}
