package scalaanalyzer.platform

import com.intellij.codeInsight.javadoc.{DocumentationDelegateProvider, JavaDocInfoGeneratorFactory}
import com.intellij.codeInsight.daemon.impl.{AnnotationHolderImpl, AnnotationSessionImpl}
import com.intellij.codeInsight.hints.settings.ParameterNameHintsSettings
import com.intellij.codeInsight.hints.{InlayParameterHintsExtension, InlayParameterHintsProvider}
import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.core.{CoreApplicationEnvironment, CoreFileTypeRegistry}
import com.intellij.ide.highlighter.HtmlFileHighlighter
import com.intellij.lang.documentation.{ClientDocumentationSettings, DocumentationSettings}
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.java.JavaLanguage
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import com.intellij.psi.{PsiErrorElement, PsiFile, SyntaxTraverser}
import com.intellij.psi.codeStyle.FileIndentOptionsProvider
import com.intellij.psi.impl.source.tree.TreeCopyHandler
import com.intellij.lang.{DefaultASTFactory, DefaultASTFactoryImpl, Language, LanguageASTFactory, LanguageCommenters, LanguageExtensionPoint, LanguageParserDefinitions, ParserDefinition, PsiBuilderFactory}
import com.intellij.lang.parser.DummyBlockWordSelectionFilter
import com.intellij.lang.impl.PsiBuilderFactoryImpl
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.{Application, ApplicationManager, ModalityState}
import com.intellij.openapi.extensions.{DefaultPluginDescriptor, Extensions, PluginId}
import com.intellij.openapi.extensions.impl.{ExtensionPointImpl, ExtensionsAreaImpl}
import com.intellij.openapi.fileTypes.{FileTypeRegistry, SyntaxHighlighter, SyntaxHighlighterFactory, SyntaxHighlighterLanguageFactory}
import com.intellij.openapi.options.advanced.{AdvancedSettings, AdvancedSettingType}
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.{EmptyProgressIndicator, ProgressManager}
import com.intellij.openapi.progress.impl.CoreProgressManager
import com.intellij.openapi.project.{DumbModeTask, DumbService, Project}
import com.intellij.openapi.util.{Condition, Disposer, Key, UserDataHolderBase}
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.featureStatistics.FeatureUsageTracker
import com.intellij.lang.parameterInfo.{LanguageParameterInfo, ParameterInfoHandler}
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.psi.{FileViewProvider, LanguageFileViewProviders, PsiClass, PsiElement, PsiField, PsiManager}
import com.intellij.psi.presentation.java.{ClassPresentationProvider, FieldPresentationProvider}
import com.intellij.psi.stubs.{LanguageStubDefinition, StubElementRegistryService, StubRegistryExtension, StubIndex}
import com.intellij.psi.impl.java.stubs.index.JavaStubIndexKeys
import com.intellij.psi.impl.cache.impl.id.{IdIndex, IdIndexEntry}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.impl.source.resolve.FileContextUtil
import com.intellij.util.Processor
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.messages.impl.MessageBusFactoryImpl
import org.jetbrains.plugins.scala.codeInsight.ScalaCodeInsightSettings
import org.jetbrains.plugins.scala.externalLibraries.bm4.BetterMonadicForSupport
import org.jetbrains.plugins.scala.externalLibraries.kindProjector.KindProjectorUtil
import org.jetbrains.plugins.scala.highlighter.{ScalaCommenter, ScalaSyntaxHighlighterFactory}
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory
import org.jetbrains.plugins.scala.project.{ProjectContext, ScalaFeatures}
import org.jetbrains.plugins.scalaDoc.ScalaDocLanguage
import org.jetbrains.plugins.scalaDoc.highlighter.ScalaDocSyntaxHighlighter
import org.jetbrains.plugins.scalaDirective.ScalaDirectiveLanguage
import org.jetbrains.plugins.scalaDirective.highlighter.ScalaDirectiveSyntaxHighlighter
import scalaanalyzer.{HostConfig, ModuleModel, ScalaPsiHost}
import scalaanalyzer.language.{ChangeEngine, CodeActions, Completion, InlayHints, Inspections, Rename}
import scalaanalyzer.platform.{Descriptors, ExtensionBean, dispatch, tableProxy}
import scalaanalyzer.ScalaPsiHost.{CompletionWeighers, SemanticExtensionPoints, scalaCompletionContributors}

import java.io.File
import java.nio.file.{Files, Path}
import java.util.{Set as JavaSet}
import java.util.concurrent.ConcurrentHashMap

import scala.jdk.CollectionConverters.*

/** Installs application services, language registries, project services and PSI startup in dependency order. */
private[scalaanalyzer] object HostBootstrap {
  /** Preserves descriptor ordering anchors while leaving completion statistics inert. */
  private val PlatformCompletionWeighers: Seq[ExtensionBean] = Seq(
    ("StatisticsWeigher", "stats", "after prefix"), ("PriorityWeigher", "priority", "first"),
    ("PrefixMatchingWeigher", "prefix", "after priority"), ("GroupingWeigher", "grouping", "last"),
    ("ExplicitProximityWeigher", "explicitProximity", "after stats"), ("LookupElementProximityWeigher", "proximity", "after explicitProximity"),
  ).map { (name, id, order) => ExtensionBean(Map("key" -> "completion", "id" -> id, "order" -> order,
    "implementationClass" -> s"com.intellij.codeInsight.completion.$name")) }

  private val JavaCompletionContributors = Seq(ExtensionBean(Map("language" -> "JAVA",
    "implementationClass" -> "com.intellij.codeInsight.completion.JavaCompletionContributor", "id" -> "java")))

  /** Installs the process-wide platform and returns the ready PSI host. */
  def start(config: HostConfig): ScalaPsiHost = {
    // PathManager reads these on first use; the ID enumeration of index keys is persisted under the system path.
    for (kind <- Seq("home", "system", "config", "log")) {
      val dir = config.stateDir.resolve(kind)
      Files.createDirectories(dir)
      System.setProperty(s"idea.$kind.path", dir.toString)
    }
    com.intellij.openapi.diagnostic.Logger.setFactory((category: String) => new HostLogger(category))
    val root: Disposable = Disposer.newDisposable("scala-analyzer")
    try {
      val host = new Bootstrap(config, root).start()
      Runtime.getRuntime.addShutdownHook(new Thread(() => Disposer.dispose(root), "scala-analyzer-dispose"))
      host
    } catch {
      case failure: Throwable =>
        try Disposer.dispose(root)
        catch { case cleanup: Throwable => failure.addSuppressed(cleanup) }
        throw failure
    }
  }

  /** The ordered installation state shared by the four bootstrap phases. */
  private final class Bootstrap(private val config: HostConfig, private val root: Disposable) {
    private val appServices = new ServiceTable("Application")
    private val completionContributors = (scalaCompletionContributors(config.scalaPluginJars) ++ JavaCompletionContributors).map(_.implementation).toSet
    private val projectServices = new ServiceTable("Project")
    private val appData = new UserDataHolderBase
    private val projectData = new UserDataHolderBase

    // An inert in-process bus: platform classes subscribe in constructors; the host never publishes IDE events.
    private val busOwner = tableProxy(classOf[com.intellij.util.messages.MessageBusOwner], "MessageBusOwner") {
      case ("isDisposed", _) => java.lang.Boolean.FALSE
    }
    private lazy val appBus = MessageBusFactoryImpl.createRootBus(busOwner)
    private lazy val area: ExtensionsAreaImpl = new ExtensionsAreaImpl(app)
    /** Uses the preview thread's copy-local write path, otherwise the host's project write lock. */
    private def runWriteAction[T](body: => T): T =
      if (IntentionPreviewUtils.isIntentionPreviewActive) body else HostLock.write(body)

    // ApplicationEx, the interface the IDE's application implements: platform utilities cast to it.
    private lazy val app: Application = tableProxy(classOf[com.intellij.openapi.application.ex.ApplicationEx], "Application") {
      case ("getService", Array(c: Class[?])) => appServices.get(c)
      case ("getServiceIfCreated", Array(c: Class[?])) => appServices.get(c)
      case ("getExtensionArea", _) => area
      case ("getMessageBus", _) => appBus
      // UI dispatch and access ownership are supplied by the host's threads and lock.
      case ("isDispatchThread", _) => java.lang.Boolean.valueOf(HostDispatchThread.isCurrent)
      case ("isWriteIntentLockAcquired", _) => java.lang.Boolean.valueOf(HostLock.hasWriteIntent)
      case ("assertWriteIntentLockAcquired", _) =>
        if (!HostLock.hasWriteIntent) throw new IllegalStateException("write intent is required")
        null
      case ("runWriteIntentReadAction", Array(c: com.intellij.openapi.util.ThrowableComputable[?, ?])) =>
        HostLock.writeIntent(c.compute()).asInstanceOf[AnyRef]
      case ("addReadActionListener", Array(listener: com.intellij.openapi.application.ReadActionListener, parent: Disposable)) =>
        HostLock.addReadListener(listener, parent)
        null
      case ("addWriteIntentReadActionListener", Array(listener: com.intellij.openapi.application.WriteIntentReadActionListener, parent: Disposable)) =>
        HostLock.addIntentListener(listener, parent)
        null
      case ("addWriteActionListener", Array(listener: com.intellij.openapi.application.WriteActionListener, parent: Disposable)) =>
        HostLock.addWriteListener(listener, parent)
        null
      // Reads and writes are the host's lock (HostLock): access is allowed where the thread holds it.
      case ("isReadAccessAllowed", _) => java.lang.Boolean.valueOf(HostLock.isReading || HostLock.hasWriteIntent)
      case ("assertReadAccessAllowed", _) =>
        if (!HostLock.isReading && !HostLock.hasWriteIntent) throw new IllegalStateException("read access is allowed inside read or write actions only")
        null
      case ("isWriteAccessAllowed", _) => java.lang.Boolean.valueOf(HostLock.isWriting)
      // The reference search stops its readers when a write starts. An edit cancels the running queries (HostLock),
      // so there is no write pending while a query reads, and nothing to listen for.
      case ("isWriteActionPending", _) => java.lang.Boolean.FALSE
      case ("addApplicationListener", _) => null
      case ("executeByImpatientReader", Array(r: Runnable)) =>
        r.run()
        null
      // Work for the UI thread runs on the host's dispatch thread.
      case ("invokeLater", Array(r: Runnable, _*)) =>
        HostDispatchThread.invokeLater(r)
        null
      // No modal progress exists: the task runs where it was asked, and is never cancelled by a user.
      case ("runProcessWithProgressSynchronously", Array(task: Runnable, _*)) =>
        task.run()
        java.lang.Boolean.TRUE
      // No modal dialogs exist in the host.
      case ("getDefaultModalityState", _) => ModalityState.nonModal()
      case ("assertWriteAccessAllowed", _) =>
        if (!HostLock.isWriting) throw new IllegalStateException("write access is allowed inside write actions only")
        null
      case ("runWriteAction", Array(r: Runnable)) =>
        runWriteAction(r.run())
        null
      case ("runWriteAction", Array(c: com.intellij.openapi.util.Computable[?])) => runWriteAction(c.compute()).asInstanceOf[AnyRef]
      case ("runWriteAction", Array(c: com.intellij.openapi.util.ThrowableComputable[?, ?])) => runWriteAction(c.compute()).asInstanceOf[AnyRef]
      case ("assertIsNonDispatchThread" | "assertReadAccessNotAllowed", _) => null
      case ("isUnitTestMode", _) => java.lang.Boolean.valueOf(config.unitTestMode || Rename.isAnsweringDialogs)
      case ("isInternal" | "isEAP" | "isDisposed", _) => java.lang.Boolean.FALSE
      case ("isHeadlessEnvironment" | "isCommandLine", _) => java.lang.Boolean.TRUE
      case ("getUserData", Array(k: Key[?])) => appData.getUserData(k).asInstanceOf[AnyRef]
      // Extension points resolve their declared class through the component manager.
      case ("loadClass", Array(name: String, _)) => Class.forName(name)
      case ("isInjectionForExtensionSupported", _) => java.lang.Boolean.FALSE
      // Completion bean extensions use the platform's lazy loader, without container injection.
      case ("instantiateClass", Array(cls: Class[?], _)) if
          completionContributors(cls.getName) ||
          ScalaPsiHost.CompletionWeighers.contains(cls.getName) ||
          Set("com.intellij.codeInsight.completion.CompletionContributorEP", "com.intellij.psi.WeigherExtensionPoint").contains(cls.getName) =>
        newPrivate(cls).asInstanceOf[AnyRef]
      case ("instantiateClass", Array(name: String, _)) if
          completionContributors(name) || ScalaPsiHost.CompletionWeighers(name) ||
          PlatformCompletionWeighers.exists(_.implementation == name) =>
        newPrivate(Class.forName(name))
      // Wraps an extension's failure for logging; the IDE adds plugin attribution, which the host does not track.
      case ("createError", Array(t: Throwable, _*)) => new RuntimeException(t)
      case ("runReadAction", Array(r: Runnable)) =>
        HostLock.readLocked(r.run())
        null
      case ("tryRunReadAction", Array(r: Runnable)) =>
        HostLock.readLocked(r.run())
        java.lang.Boolean.TRUE
      // In unit-test mode the plugin runs some startup work on the UI thread: the host's dispatch thread.
      case ("invokeAndWait", Array(r: Runnable, _*)) if config.unitTestMode =>
        HostDispatchThread.invokeAndWait(r)
        null
      case ("runReadAction", Array(c: com.intellij.openapi.util.Computable[?])) => HostLock.readLocked(c.compute()).asInstanceOf[AnyRef]
      case ("runReadAction", Array(c: com.intellij.openapi.util.ThrowableComputable[?, ?])) => HostLock.readLocked(c.compute()).asInstanceOf[AnyRef]
    }

    private lazy val epDecls: Map[String, EpDecl] = {
      val classpathJars = System.getProperty("java.class.path").split(File.pathSeparator).toSeq
        .filter(_.endsWith(".jar")).map(Path.of(_))
      Descriptors.extensionPoints(classpathJars, config.scalaPluginJars)
    }

    /** Declares a descriptor-backed extension point once and returns its platform registry. */
    private def declare(name: String): ExtensionPointImpl[?] = {
      val declaration = epDecls.getOrElse(name, throw new IllegalStateException(s"extension point is not declared on the classpath: $name"))
      area.registerExtensionPoint(declaration.name, declaration.className, declaration.kind, declaration.dynamic)
      area.getExtensionPoint(name).asInstanceOf[ExtensionPointImpl[?]]
    }

    private lazy val symbolIndex = new SymbolIndex
    private lazy val libraryFiles = {
      val files = new LibraryFileSystem
      Disposer.register(root, (() => files.close()): Disposable)
      files
    }
    private lazy val librarySources = new ConcurrentHashMap[VirtualFile, VirtualFile]()

    private lazy val psiModTracker = new com.intellij.psi.impl.PsiModificationTrackerImpl(project)
    private lazy val projectBus = new MessageBusFactoryImpl().createMessageBus(busOwner, appBus)
    private lazy val moduleModel = new ModuleModel(project)
    private lazy val project: Project = tableProxy(classOf[Project], "Project") {
      case ("getService", Array(c: Class[?])) => projectServices.get(c)
      case ("getServiceIfCreated", Array(c: Class[?])) => projectServices.get(c)
      case ("getExtensionArea", _) => area
      case ("getMessageBus", _) => projectBus
      case ("getName", _) => "scala-analyzer"
      case ("getBasePath", _) => "/scala-analyzer"
      case ("getLocationHash", _) => "scala-analyzer"
      case ("isDisposed", _) => java.lang.Boolean.FALSE
      case ("isOpen" | "isInitialized", _) => java.lang.Boolean.TRUE
      case ("isDefault", _) => java.lang.Boolean.FALSE
      case ("getUserData", Array(k: Key[?])) => projectData.getUserData(k).asInstanceOf[AnyRef]
      case ("putUserData", Array(k: Key[?], v)) =>
        projectData.putUserData(k.asInstanceOf[Key[AnyRef]], v)
        null
    }
    private lazy val wordIndex = new WordIndex(project)
    private lazy val importIndex = new ImportIndex(project)

    /** Runs all four phases before exposing the host to callers. */
    def start(): ScalaPsiHost = {
      installApplication()
      installLanguageRegistries()
      installProjectServices()
      startProject()
    }

    /** Installs the application object, services, and descriptor-backed extension points. */
    private def installApplication(): Unit = {
      /** Registers completion descriptor beans while retaining their platform IDs and ordering metadata. */
      def registerCompletionBeans(epName: String, beans: Seq[ExtensionBean], plugin: com.intellij.openapi.extensions.PluginDescriptor): Unit = {
        val ep = area.getExtensionPoint(epName).asInstanceOf[ExtensionPointImpl[?]]
        val descriptors = beans.map { bean =>
          val attributes = bean.attributes -- (if (epName.endsWith("weigher")) Seq("order") else Seq("id", "order"))
          new com.intellij.openapi.extensions.ExtensionDescriptor(null, null, bean.attributes.getOrElse("id", null),
            com.intellij.openapi.extensions.LoadingOrder.readOrder(bean.attributes.getOrElse("order", "")),
            new com.intellij.util.xml.dom.XmlElement(epName.stripPrefix("com.intellij."), attributes.asJava, java.util.List.of(), null), true)
        }
        ep.registerExtensions(descriptors.asJava, plugin, java.util.List.of())
      }

      // ---- 1. Application object -------------------------------------------------------------
      ApplicationManager.setApplication(app, root)
      Extensions.setRootArea(area)
      // The plugin's own extension points, with no extensions.
      val pluginEps = epDecls.values.filter(_.fromScalaPlugin)
      if (pluginEps.isEmpty)
        throw new IllegalStateException(s"no extension points declared by the Scala plugin jars ${config.scalaPluginJars.mkString(", ")}; are they on the classpath?")
      pluginEps.foreach(d => declare(d.name))
      // The base handlers in intellij.platform.lang.impl.xml also run for Scala, before the plugin's handlers.
      declare(ExtendWordSelectionHandler.EP_NAME.getName)
      val selectioners = area.getExtensionPoint[ExtendWordSelectionHandler](ExtendWordSelectionHandler.EP_NAME.getName)
      val baseSelectioners = Seq("PlainTextLineSelectioner", "NaturalLanguageTextSelectioner", "WordSelectioner", "LineCommentSelectioner",
        "BlockCommentSelectioner", "InjectedFileReferenceSelectioner").map("com.intellij.codeInsight.editorActions.wordSelection." + _) :+
        "com.intellij.ide.highlighter.custom.impl.CustomFileTypeSelectWordHandler"
      for (name <- baseSelectioners) {
        val handler = newPrivate(Class.forName(name))
        selectioners.registerExtension(classOf[ExtendWordSelectionHandler].cast(handler), root)
      }
      // Mod-command intentions copy PSI through the platform preview pipeline.
      declare("com.intellij.modCommandCopyHandler")
        .asInstanceOf[ExtensionPointImpl[com.intellij.modcommand.ModCommandService.ModCommandPsiCopyHandler]]
        .registerExtension(new com.intellij.modcommand.ModCommandService.ModCommandPsiCopyHandler {
          override def createCopy(original: PsiFile): PsiFile = ChangeEngine.copyForModCommand(original)
        }, root)
      declare("com.intellij.basicWordSelectionFilter")
      val wordFilters = area.getExtensionPoint[Condition[PsiElement]]("com.intellij.basicWordSelectionFilter")
      wordFilters.registerExtension(new DummyBlockWordSelectionFilter, root)
      // Searches ask for use scope enlargers. The plugin's only one widens the scope to the IDE's sbt shared-source
      // modules, which a build server never reports, and it needs the IDE's module types; the host registers none.
      declare("com.intellij.useScopeEnlarger")
      // The plugin's extension points whose extensions are language semantics, loaded as its descriptors register
      // them. syntheticMemberInjector: case-class and companion members, context bounds, enums, `derives`, and the
      // members library macros generate (Monocle, Circe, Simulacrum, ...), as the IDE always runs them.
      for (epName <- ScalaPsiHost.SemanticExtensionPoints) {
        val implementations = Descriptors.scalaExtensions(config.scalaPluginJars, epName)
        if (implementations.isEmpty) throw new IllegalStateException(s"no extensions registered for $epName in the Scala plugin's descriptors")
        // A platform extension point (the plugin's own are declared above) is declared on first use.
        val declared = Option(area.getExtensionPointIfRegistered[AnyRef](epName)).getOrElse(declare(epName))
        val ep = declared.asInstanceOf[ExtensionPointImpl[AnyRef]]
        implementations.foreach(name => ep.registerExtension(newPrivate(Class.forName(name).asInstanceOf[Class[AnyRef]]), root))
      }
      // Rename: the plugin's processors, in the order its descriptors give (the first that accepts an element renames it),
      // and its veto of elements that have no name to change. Validators of new names: the language's names validator.
      val renameProcessors = declare("com.intellij.renamePsiElementProcessor").asInstanceOf[ExtensionPointImpl[AnyRef]]
      for (bean <- Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.renamePsiElementProcessor")) {
        val processor = newPrivate(Class.forName(bean.implementation).asInstanceOf[Class[AnyRef]])
        renameProcessors.registerExtension(processor, com.intellij.openapi.extensions.LoadingOrder.readOrder(bean.attributes.getOrElse("order", "")), root)
      }
      // Renaming a reference in a Java source builds the new identifier with the Java tree generator.
      declare("com.intellij.treeGenerator").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new com.intellij.psi.impl.source.tree.JavaTreeGenerator, root)
      // Java sources of mixed projects rename with the Java plugin's processors, after the Scala ones that extend them.
      for (name <- Seq("RenameJavaClassProcessor", "RenameJavaMethodProcessor", "RenameJavaVariableProcessor"))
        renameProcessors.registerExtension(newPrivate(Class.forName(s"com.intellij.refactoring.rename.$name").asInstanceOf[Class[AnyRef]]), root)
      declare("com.intellij.renameInputValidator")
      val renameVetoes = declare("com.intellij.vetoRenameCondition").asInstanceOf[ExtensionPointImpl[AnyRef]]
      for (name <- Descriptors.scalaExtensions(config.scalaPluginJars, "com.intellij.vetoRenameCondition"))
        renameVetoes.registerExtension(newPrivate(Class.forName(name).asInstanceOf[Class[AnyRef]]), root)
      // The deep inheritors search, which the plugin's overriders search relies on: it walks the direct inheritors that the
      // plugin's searcher gives. (The Java direct searcher reads compiler indices, and would find Java subclasses only.)
      area.getExtensionPoint[com.intellij.util.QueryExecutor[com.intellij.psi.PsiClass, com.intellij.psi.search.searches.ClassInheritorsSearch.SearchParameters]](
        "com.intellij.classInheritorsSearch").registerExtension(new com.intellij.psi.impl.search.JavaClassInheritorsSearcher, root)
      // The references to a Java method, and its overriders in Java classes and in Scala ones (through the inheritors search).
      area.getExtensionPoint[AnyRef]("com.intellij.methodReferencesSearch").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new com.intellij.psi.impl.search.MethodUsagesSearcher, root)
      area.getExtensionPoint[AnyRef]("com.intellij.overridingMethodsSearch").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new com.intellij.psi.impl.search.JavaOverridingMethodsSearcher, root)
      // The Java rename processors look for the base methods of a method through the super-methods searches.
      CoreApplicationEnvironment.registerExtensionPoint(area,
        com.intellij.java.codeserver.highlighting.JavaErrorFilter.EP_NAME.getName,
        classOf[com.intellij.java.codeserver.highlighting.JavaErrorFilter])
      declare("com.intellij.implicitClassSearch").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new com.intellij.psi.impl.search.ImplicitClassesSearcher, root)
      declare("com.intellij.testSourcesFilter").asInstanceOf[ExtensionPointImpl[com.intellij.openapi.roots.TestSourcesFilter]]
        .registerExtension(newPrivate(Class.forName("com.intellij.openapi.roots.ProjectRootTestSourcesFilter")
          .asSubclass(classOf[com.intellij.openapi.roots.TestSourcesFilter])), root)
      declare("com.intellij.inspectionElementsMerger")
      declare("com.intellij.globalInspection")
      declare("com.intellij.deadCode")
      declare("com.intellij.implicitUsageProvider")
      appServices.register(classOf[com.intellij.codeInspection.SuppressManager])(
        newPrivate(Class.forName("com.intellij.codeInspection.SuppressManagerImpl")
          .asSubclass(classOf[com.intellij.codeInspection.SuppressManager])))
      declare("com.intellij.superMethodsSearch").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new com.intellij.psi.impl.search.MethodSuperSearcher, root)
      declare("com.intellij.deepestSuperMethodsSearch").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new com.intellij.psi.impl.search.MethodDeepestSuperSearcher, root)
      // Descriptor beans preserve both the implementation attributes and the platform's id/order semantics.
      val completionPlugin = new DefaultPluginDescriptor(PluginId.getId("org.intellij.scala"))
      val weighers = Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.weigher").filter { bean =>
        ScalaPsiHost.CompletionWeighers(bean.implementation) && Set("completion", "proximity").contains(bean.attributes.getOrElse("key", ""))
      }
      for ((epName, beans) <- Seq("com.intellij.completion.contributor" -> scalaCompletionContributors(config.scalaPluginJars),
                                  "com.intellij.weigher" -> weighers)) {
        declare(epName)
        if (beans.isEmpty) throw new IllegalStateException(s"no completion beans for $epName")
        registerCompletionBeans(epName, beans, completionPlugin)
      }
      registerCompletionBeans("com.intellij.completion.contributor", JavaCompletionContributors,
        new DefaultPluginDescriptor(PluginId.getId("com.intellij.java")))
      registerCompletionBeans("com.intellij.weigher", PlatformCompletionWeighers, new DefaultPluginDescriptor(PluginId.getId("com.intellij")))
      for (name <- Seq("com.intellij.modcompletion.completionItemProvider", "com.intellij.modcompletion.completionItemFilter"))
        CoreApplicationEnvironment.registerExtensionPoint(area, name, classOf[LanguageExtensionPoint[?]])
      com.intellij.modcompletion.ModCompletionItemProvider.EP_NAME.addExplicitExtension(JavaLanguage.INSTANCE,
        newPrivate(Class.forName("com.intellij.java.completion.modcommand.KeywordCompletionItemProvider")
          .asSubclass(classOf[com.intellij.modcompletion.ModCompletionItemProvider])))
      CoreApplicationEnvironment.registerExtensionPoint(area,
        com.intellij.java.impl.template.JavaTemplateCodeInsightSupport.EP_NAME.getName,
        classOf[com.intellij.java.impl.template.JavaTemplateCodeInsightSupport])
      projectServices.register(classOf[com.intellij.codeInsight.AutoPopupControllerHelper])(
        tableProxy(classOf[com.intellij.codeInsight.AutoPopupControllerHelper], "AutoPopupControllerHelper") {
          case ("autoPopupParameterInfoAfterCompletion", _) => CallLog.hit("Completion.popup.parameterInfo"); null
        })
      // These popup requests are optional UI hints; recording intent leaves the synchronous insertion valid without a popup.
      projectServices.register(classOf[com.intellij.codeInsight.AutoPopupController])(new com.intellij.codeInsight.AutoPopupController {
        override def autoPopupParameterInfo(editor: com.intellij.openapi.editor.Editor, element: com.intellij.psi.PsiElement): Unit =
          CallLog.hit("Completion.popup.parameterInfo")
        override def scheduleAutoPopup(editor: com.intellij.openapi.editor.Editor, kind: com.intellij.codeInsight.completion.CompletionType,
                                      condition: com.intellij.openapi.util.Condition[? >: PsiFile]): Unit =
          CallLog.hit("Completion.popup.completion")
        override def autoPopupMemberLookup(editor: com.intellij.openapi.editor.Editor,
                                          condition: com.intellij.openapi.util.Condition[? >: PsiFile]): Unit =
          scheduleAutoPopup(editor, com.intellij.codeInsight.completion.CompletionType.BASIC, condition)
        override def autoPopupMemberLookup(editor: com.intellij.openapi.editor.Editor, kind: com.intellij.codeInsight.completion.CompletionType,
                                          condition: com.intellij.openapi.util.Condition[? >: PsiFile]): Unit =
          scheduleAutoPopup(editor, kind, condition)
        override def cancelAllRequests(): Unit = CallLog.unsupported("AutoPopupController.cancelAllRequests")
        override def waitForDelayedActions(timeout: Long, unit: java.util.concurrent.TimeUnit): Unit =
          CallLog.unsupported("AutoPopupController.waitForDelayedActions")
      })
      appServices.register(classOf[com.intellij.codeInsight.completion.CompletionService])(new Completion.Service)
      // Tab-out scopes are interactive editor state; LSP insertion returns edits and a caret instead.
      appServices.register(classOf[com.intellij.codeInsight.editorActions.TabOutScopesTracker])(
        tableProxy(classOf[com.intellij.codeInsight.editorActions.TabOutScopesTracker], "TabOutScopesTracker") {
          case ("registerScopeRange", _) => CallLog.hit("Completion.tabOutScopeSkipped"); null
        })
      // Ranking is deterministic and has no persisted selection history in the host.
      appServices.register(classOf[com.intellij.psi.statistics.StatisticsManager])(
        new com.intellij.psi.statistics.StatisticsManager {
          override def getUseCount(info: com.intellij.psi.statistics.StatisticsInfo): Int = 0
          override def getLastUseRecency(info: com.intellij.psi.statistics.StatisticsInfo): Int = Int.MaxValue
          override def getAllValues(context: String): Array[com.intellij.psi.statistics.StatisticsInfo] = Array.empty
          override def incUseCount(info: com.intellij.psi.statistics.StatisticsInfo): Unit = CallLog.hit("Completion.usageSkipped")
        })
      appServices.register(classOf[com.intellij.util.Queries])(new com.intellij.util.QueriesImpl)
      appServices.register(classOf[com.intellij.util.gist.GistManager])(
        new HostGistManager(() => HostDispatchThread.invokeLater(() => psiChanged())))
      appServices.register(classOf[com.intellij.codeInsight.completion.InternalCompletionSettings])(
        new com.intellij.codeInsight.completion.InternalCompletionSettings)
      appServices.register(classOf[com.intellij.codeInsight.TailTypeFactory])(
        new com.intellij.codeInsight.completion.FrontendFriendlyTailTypeFactory)
      // No IDE preference store; editor settings use their own defaults when this optional service is absent.
      appServices.registerNull(classOf[com.intellij.ide.util.PropertiesComponent])
      appServices.register(classOf[com.intellij.openapi.editor.ex.EditorSettingsExternalizable])(
        new com.intellij.openapi.editor.ex.EditorSettingsExternalizable(
          new com.intellij.openapi.editor.ex.EditorSettingsExternalizable.OsSpecificState))
      appServices.register(classOf[com.intellij.codeInsight.CodeInsightSettings])(new com.intellij.codeInsight.CodeInsightSettings)
      appServices.register(classOf[com.intellij.codeInsight.JavaIdeCodeInsightSettings])(
        new com.intellij.codeInsight.JavaIdeCodeInsightSettings)
      appServices.register(classOf[ScalaCodeInsightSettings])(new ScalaCodeInsightSettings)
      // The IDE shows implicit arguments and conversions while the user toggles them on; an LSP client can only show
      // or hide inlay hints as a whole, so they are on.
      InlayHints.setImplicitHintsEnabled(true)
      appServices.register(classOf[ParameterNameHintsSettings])(new ParameterNameHintsSettings)
      // Quick documentation becomes Markdown, which the editor highlights itself: the HTML carries no colors.
      CoreApplicationEnvironment.registerExtensionPoint(area, DocumentationDelegateProvider.EP_NAME.getName,
        classOf[DocumentationDelegateProvider])
      appServices.register(classOf[JavaDocInfoGeneratorFactory])(new JavaDocInfoGeneratorFactory)
      appServices.register(classOf[ClientDocumentationSettings])(new ClientDocumentationSettings {
        override def isHighlightingOfQuickDocSignaturesEnabled: Boolean = false
        override def isHighlightingOfCodeBlocksEnabled: Boolean = false
        override def isSemanticHighlightingOfLinksEnabled: Boolean = false
        override def isCodeBackgroundEnabled: Boolean = false
        override def getInlineCodeHighlightingMode: DocumentationSettings.InlineCodeHighlightingMode =
          DocumentationSettings.InlineCodeHighlightingMode.NO_HIGHLIGHTING
      })
      // Usage statistics behind the IDE's tips ("did you know..."): the host records no use and advertises nothing.
      appServices.register(classOf[FeatureUsageTracker])(new FeatureUsageTracker {
        override def triggerFeatureUsed(featureId: String): Unit = ()
        override def triggerFeatureUsedByAction(actionId: String): Unit = ()
        override def triggerFeatureUsedByIntention(intentionClass: Class[?]): Unit = ()
        override def triggerFeatureShown(featureId: String): Unit = ()
        override def isToBeShown(featureId: String, project: Project): Boolean = false
        override def isToBeAdvertisedInLookup(featureId: String, project: Project): Boolean = false
      })
      // Lookup presentation checks test-class icons; no test-framework integrations are installed in the host.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.testIntegration.TestFramework.EXTENSION_NAME,
        classOf[com.intellij.testIntegration.TestFramework])
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.javaMainMethodProvider",
        classOf[com.intellij.codeInsight.runner.JavaMainMethodProvider])
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.packageDirectoryProvider",
        classOf[com.intellij.psi.PackageDirectoryProvider])
      appServices.register(classOf[com.intellij.codeInsight.TestFrameworks])(newPrivate(classOf[com.intellij.codeInsight.TestFrameworksImpl]))
      appServices.registerNull(classOf[com.intellij.codeInsight.completion.NewRdCompletionSupport])
      appServices.register(classOf[com.intellij.codeInsight.completion.group.GroupedCompletion])(
        tableProxy(classOf[com.intellij.codeInsight.completion.group.GroupedCompletion], "GroupedCompletion") {
          case ("isEnabled", _) => java.lang.Boolean.FALSE
        })
      // Platform extension points the plugin's PSI queries, with no extensions. Their declarations live in IDE
      // descriptors that are not on this classpath; the platform's own core environment declares them in code too.
      CoreApplicationEnvironment.registerExtensionPoint(area, FileIndentOptionsProvider.EP_NAME, classOf[FileIndentOptionsProvider])
      CoreApplicationEnvironment.registerExtensionPoint(area, TreeCopyHandler.EP_NAME, classOf[TreeCopyHandler])
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.multiverseEnabler", classOf[com.intellij.codeInsight.multiverse.MultiverseEnabler])
      // Java PSI of library classes asks for augmentations (Lombok-style); there are none.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.psi.augment.PsiAugmentProvider.EP_NAME, classOf[com.intellij.psi.augment.PsiAugmentProvider])
      // Which syntax errors are shown: not inside ScalaDoc, and not in files whose errors come from the compiler.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.codeInsight.highlighting.HighlightErrorFilter.EP_NAME.getName,
        classOf[com.intellij.codeInsight.highlighting.HighlightErrorFilter])
      locally {
        val filters = area.getExtensionPoint[com.intellij.codeInsight.highlighting.HighlightErrorFilter](
          com.intellij.codeInsight.highlighting.HighlightErrorFilter.EP_NAME.getName)
        filters.registerExtension(new org.jetbrains.plugins.scala.codeInsight.highlighting.ScalaDocHighlightErrorFilter, root)
        // The plugin's CompilerHighlightingErrorFilter lives in its compiler-integration module (JPS, compile server),
        // which the host does not load; this is the same rule, decided by the plugin's ScalaHighlightingMode.
        filters.registerExtension(new com.intellij.codeInsight.highlighting.HighlightErrorFilter {
          override def shouldHighlightErrorElement(element: com.intellij.psi.PsiErrorElement): Boolean =
            !org.jetbrains.plugins.scala.settings.ScalaHighlightingMode.isShowErrorsFromCompilerEnabled(element.getContainingFile)
        }, root)
      }
      // Custom folding regions in comments (`//region` ... `//endregion`, `// <editor-fold>`): the IDE's two providers.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.lang.folding.CustomFoldingProvider.EP_NAME,
        classOf[com.intellij.lang.folding.CustomFoldingProvider])
      locally {
        val providers = area.getExtensionPoint[com.intellij.lang.folding.CustomFoldingProvider](com.intellij.lang.folding.CustomFoldingProvider.EP_NAME.getName)
        providers.registerExtension(new com.intellij.lang.customFolding.NetBeansCustomFoldingProvider, root)
        providers.registerExtension(new com.intellij.lang.customFolding.VisualStudioCustomFoldingProvider, root)
      }
      // Filters of highlighting results (the IDE's hide some in debugger and diff views); the plugin registers none.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.codeInsight.daemon.impl.HighlightInfoFilter.EXTENSION_POINT_NAME,
        classOf[com.intellij.codeInsight.daemon.impl.HighlightInfoFilter])
      // Languages that stand for groups of languages in language extensions; none in the host.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.lang.MetaLanguage.EP_NAME, classOf[com.intellij.lang.MetaLanguage])
      // Smart pointers' anchors and element icons: no providers in the host.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider.EP_NAME,
        classOf[com.intellij.psi.impl.smartPointers.SmartPointerAnchorProvider])
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.ide.IconProvider.EXTENSION_POINT_NAME, classOf[com.intellij.ide.IconProvider])
      // Java class files consult optional navigation policies before the host's attached-source lookup.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy.EP_NAME,
        classOf[com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy])

      appServices.register(classOf[ProgressManager])(new CoreProgressManager)
      appServices.register(classOf[PsiBuilderFactory])(new PsiBuilderFactoryImpl)
      // The builder of the platform's newer syntax layer, which the Java parser uses.
      appServices.register(classOf[com.intellij.platform.syntax.psi.PsiSyntaxBuilderFactory])(
        new com.intellij.platform.syntax.psi.PsiSyntaxBuilderFactoryImpl)
      appServices.register(classOf[DefaultASTFactory])(new DefaultASTFactoryImpl)
      // In-memory documents; in the LSP host these become the client's snapshots.
      appServices.register(classOf[com.intellij.openapi.fileEditor.FileDocumentManager])(
        // Documents for use off the UI thread (the platform's option for background documents): the host has no UI
        // thread, and its write action keeps writers apart.
        new com.intellij.mock.MockFileDocumentManagerImpl(null, (text: CharSequence) =>
          new com.intellij.openapi.editor.impl.DocumentImpl(text, /* forUseInNonAWTThread = */ true)))
      // The sets of files a package scope holds.
      appServices.register(classOf[com.intellij.openapi.vfs.VirtualFileSetFactory])(new com.intellij.openapi.vfs.VirtualFileSetFactory {
        override def createCompactVirtualFileSet(): com.intellij.openapi.vfs.VirtualFileSet = new HostVirtualFileSet
        override def createCompactVirtualFileSet(files: java.util.Collection[? <: VirtualFile]): com.intellij.openapi.vfs.VirtualFileSet =
          new HostVirtualFileSet(new java.util.LinkedHashSet[VirtualFile](files))
      })
      // Library text defaults to UTF-8, with no per-file charset override.
      appServices.register(classOf[com.intellij.openapi.vfs.encoding.EncodingManager])(new Host_EncodingManager(dispatch {
        case ("EncodingManager.getEncoding", _) => null
        case ("EncodingManager.getDefaultCharset", _) => java.nio.charset.StandardCharsets.UTF_8
      }))
      // Transformations between a file's bytes and its text presentation, by file type; none for Scala or Java.
      declare(com.intellij.openapi.vfs.transformer.TextPresentationTransformers.Companion.getEP.getName)
      appServices.register(classOf[com.intellij.openapi.vfs.transformer.TextPresentationTransformers])(
        new com.intellij.openapi.vfs.transformer.TextPresentationTransformers)
      // PSI writes check command state; the core processor tracks it without UI or undo.
      appServices.register(classOf[com.intellij.openapi.command.CommandProcessor])(new com.intellij.openapi.command.impl.CoreCommandProcessor)
      // Legacy inspection fixes may prepare writable files only inside their isolated preview session.
      appServices.register(classOf[com.intellij.codeInsight.FileModificationService])(new com.intellij.codeInsight.FileModificationService {
        override def prepareFileForWrite(file: PsiFile): Boolean =
          file != null && IntentionPreviewUtils.isIntentionPreviewActive && IntentionPreviewUtils.isPreviewElement(file)
        override def preparePsiElementsForWrite(elements: java.util.Collection[? <: PsiElement]): Boolean =
          elements.asScala.forall(element => prepareFileForWrite(element.getContainingFile))
        override def prepareVirtualFilesForWrite(project: Project, files: java.util.Collection[? <: VirtualFile]): Boolean =
          CallLog.unsupported("FileModificationService.prepareVirtualFilesForWrite")
      })
      // PSI versioning (MVCC): the platform's service, which with persistent syntax trees off (the registry default)
      // creates plain PSI elements during reparses.
      appServices.register(classOf[com.intellij.psi.util.PsiVersioningService])(new com.intellij.psi.impl.source.tree.mvcc.PsiVersioningServiceImpl)
      // "Run on the UI thread, carrying the write action": the host has no UI thread and the caller already holds the
      // write action, so the action runs where it is.
      appServices.register(classOf[com.intellij.util.concurrency.TransferredWriteActionService])(
        tableProxy(classOf[com.intellij.util.concurrency.TransferredWriteActionService], "TransferredWriteActionService") {
          case ("runOnEdtWithTransferredWriteActionAndWait" | "runOnBackgroundThreadWithTransferredWriteActionAndWait", Array(r: Runnable)) =>
            r.run()
            null
        })
      // Document commits: synchronous, on the editing thread.
      appServices.register(classOf[com.intellij.psi.impl.DocumentCommitProcessor])(new HostDocumentCommitProcessor)
      // Read through serviceOrNull: PSI MVCC is off by default (registry psi.enable.persistent.syntax.tree=false).
      appServices.registerNull(classOf[com.intellij.psi.impl.source.tree.mvcc.PsiVersioningGarbageCollector])
      // Optional diagnostics hook; the caller null-checks it.
      appServices.registerNull(classOf[com.intellij.psi.ParsingDiagnostics.ParserDiagnosticsHandler])
      appServices.register(classOf[com.intellij.openapi.application.AsyncExecutionService])(new HostAsyncExecutionService)
      // Private constructors: the IDE container instantiates these reflectively, and so does the host.
      appServices.register(classOf[com.intellij.psi.LanguageSubstitutors])(newPrivate(classOf[com.intellij.psi.LanguageSubstitutors]))
      // Holds one flag (external change in progress) that PSI events read.
      locally {
        val cls = Class.forName("com.intellij.psi.ExternalChangeActionUtil$PsiExternalChangeService").asInstanceOf[Class[AnyRef]]
        appServices.register(cls)(newPrivate(cls))
      }
      // The plugin's settings provider links UI classes absent from this classpath. Its customizeDefaults sets
      // these three indent sizes to 2 and KEEP_FIRST_COLUMN_COMMENT to false; the settings bean owns import defaults.
      val scalaStyleProvider = tableProxy(classOf[com.intellij.psi.codeStyle.LanguageCodeStyleProvider], "ScalaCodeStyleProvider") {
        case ("getLanguage", _) => org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE
        case ("getDefaultCommonSettings", _) =>
          val ScalaDefaultIndentSize = 2
          val common = new com.intellij.psi.codeStyle.CommonCodeStyleSettings(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE)
          val indent = common.initIndentOptions()
          indent.INDENT_SIZE = ScalaDefaultIndentSize
          indent.TAB_SIZE = ScalaDefaultIndentSize
          indent.CONTINUATION_INDENT_SIZE = ScalaDefaultIndentSize
          common.KEEP_FIRST_COLUMN_COMMENT = false
          common
        case ("getDocCommentSettings", _) => com.intellij.psi.codeStyle.DocCommentSettings.DEFAULTS
        case ("createCustomSettings", Array(settings: com.intellij.psi.codeStyle.CodeStyleSettings)) =>
          new org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings(settings)
      }
      val javaStyleProvider = tableProxy(classOf[com.intellij.psi.codeStyle.LanguageCodeStyleProvider], "JavaCodeStyleProvider") {
        case ("getLanguage", _) => JavaLanguage.INSTANCE
        case ("getDefaultCommonSettings", _) =>
          val common = new com.intellij.psi.codeStyle.CommonCodeStyleSettings(JavaLanguage.INSTANCE)
          common.initIndentOptions()
          common
        case ("getDocCommentSettings", _) => com.intellij.psi.codeStyle.DocCommentSettings.DEFAULTS
        case ("createCustomSettings", Array(settings: com.intellij.psi.codeStyle.CodeStyleSettings)) =>
          new com.intellij.psi.codeStyle.JavaCodeStyleSettings(settings)
      }
      appServices.register(classOf[com.intellij.psi.codeStyle.CodeStyleSettingsService])(
        tableProxy(classOf[com.intellij.psi.codeStyle.CodeStyleSettingsService], "CodeStyleSettingsService") {
          case ("addListener", _) => null
          case ("getFileTypeIndentOptionsFactories", _) => java.util.List.of()
          case ("getLanguageCodeStyleProviders", _) =>
            java.util.List.of(scalaStyleProvider, javaStyleProvider)
          // Language settings beans with defaults, without their settings-UI providers.
          case ("getCustomCodeStyleSettingsFactories", _) =>
            java.util.List.of(new com.intellij.psi.codeStyle.CustomCodeStyleSettingsFactory {
              override def createCustomSettings(s: com.intellij.psi.codeStyle.CodeStyleSettings) =
                new org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings(s)
            }, new com.intellij.psi.codeStyle.CustomCodeStyleSettingsFactory {
              override def createCustomSettings(s: com.intellij.psi.codeStyle.CodeStyleSettings) =
                new com.intellij.psi.codeStyle.JavaCodeStyleSettings(s)
            })
        })
      appServices.register(classOf[com.intellij.formatting.Formatter])(new com.intellij.formatting.FormatterImpl)
      appServices.register(classOf[com.intellij.formatting.FormattingProgressCallbackFactory])(
        tableProxy(classOf[com.intellij.formatting.FormattingProgressCallbackFactory], "FormattingProgressCallbackFactory") {
          // The optional UI callback is absent; cancellation uses the request's progress indicator.
          case ("createProgressCallback", _) => null
        })
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.lang.formatter", classOf[LanguageExtensionPoint[?]])
      com.intellij.lang.LanguageFormatting.INSTANCE.addExplicitExtension(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE,
        new org.jetbrains.plugins.scala.lang.formatting.ScalaFormattingModelBuilder)
      com.intellij.lang.LanguageFormatting.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE,
        new com.intellij.lang.java.JavaFormattingModelBuilder)
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.lang.importOptimizer", classOf[LanguageExtensionPoint[?]])
      com.intellij.lang.LanguageImportStatements.INSTANCE.addExplicitExtension(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE,
        new org.jetbrains.plugins.scala.editor.importOptimizer.ScalaImportOptimizer)
      for (name <- Seq("com.intellij.formattingService", "com.intellij.lang.formatter.restriction", "com.intellij.externalFormatProcessor",
                       "com.intellij.preFormatProcessor", "com.intellij.postFormatProcessor", "com.intellij.disabledIndentRangesProvider", "com.intellij.documentWriteAccessGuard")) declare(name)
      area.getExtensionPoint[com.intellij.formatting.service.FormattingService]("com.intellij.formattingService")
        .registerExtension(new com.intellij.formatting.service.CoreFormattingService, root)
      area.getExtensionPoint[com.intellij.lang.LanguageFormattingRestriction]("com.intellij.lang.formatter.restriction")
        .registerExtension(new org.jetbrains.plugins.scala.lang.formatting.ScalaLanguageFormattingRestriction, root)
      area.getExtensionPoint[com.intellij.psi.impl.source.codeStyle.PreFormatProcessor]("com.intellij.preFormatProcessor")
        .registerExtension(new org.jetbrains.plugins.scala.lang.formatting.processors.ScalaDocNewlinedPreFormatProcessor, root)
      val postProcessors = area.getExtensionPoint[com.intellij.psi.impl.source.codeStyle.PostFormatProcessor]("com.intellij.postFormatProcessor")
      postProcessors.registerExtension(new org.jetbrains.plugins.scala.lang.formatting.processors.ScalaBracePostFormatProcessor, root)
      postProcessors.registerExtension(new org.jetbrains.plugins.scala.lang.formatting.processors.ScalaTrailingCommaProcessor, root)
      val symbolIndex = this.symbolIndex
      appServices.register(classOf[com.intellij.psi.stubs.StubIndex])(symbolIndex.stubIndex)
      // The project library table, which the host never changes; LibraryExtensionsManager subscribes to it.
      lazy val libraryTable = tableProxy(classOf[com.intellij.openapi.roots.libraries.LibraryTable], "LibraryTable") {
        case ("addListener", _) => null
      }
      appServices.register(classOf[com.intellij.openapi.roots.libraries.LibraryTablesRegistrar])(new Host_LibraryTablesRegistrar(dispatch {
        case ("LibraryTablesRegistrar.getLibraryTable", Array(_: Project)) => libraryTable
      }))
      // Plugin application services: plain classes.
      appServices.register(classOf[org.jetbrains.plugins.scala.settings.ScalaApplicationSettings])(
        new org.jetbrains.plugins.scala.settings.ScalaApplicationSettings)
      // Which regions the folding builder marks as collapsed by default: the IDE's defaults.
      appServices.register(classOf[org.jetbrains.plugins.scala.settings.ScalaCodeFoldingSettings])(
        new org.jetbrains.plugins.scala.settings.ScalaCodeFoldingSettingsImpl)
      appServices.register(classOf[org.jetbrains.plugins.scala.lang.psi.api.expr.ExpectedTypes])(
        new org.jetbrains.plugins.scala.lang.psi.impl.expr.ExpectedTypesImpl)
      appServices.register(classOf[org.jetbrains.plugins.scala.scalaMeta.ScalaMetaApi])(new scala.meta.intellij.ScalaMetaApiImpl)
      // The editor's read/write-action facade, reached from CodeStyle.getSettings: the host's callers may always read.
      appServices.register(classOf[com.intellij.openapi.editor.elf.Elf])(tableProxy(classOf[com.intellij.openapi.editor.elf.Elf], "Elf") {
        case ("runReadAction", Array(f: kotlin.jvm.functions.Function0[?])) => HostLock.readLocked(f.invoke()).asInstanceOf[AnyRef]
        // Documents are plain platform documents, outside the editor's Elf scope.
        case ("isInElfScope", _) => java.lang.Boolean.FALSE
      })
      // Annotations fetch the global color scheme for presentation (inlay hints, error-stripe-only attributes). The
      // host presents nothing: every key has empty attributes.
      lazy val noColors = tableProxy(classOf[com.intellij.openapi.editor.colors.EditorColorsScheme], "EditorColorsScheme") {
        case ("getAttributes", _) => new com.intellij.openapi.editor.markup.TextAttributes()
      }
      appServices.register(classOf[com.intellij.openapi.editor.colors.EditorColorsManager])(new Host_EditorColorsManager(dispatch {
        case ("EditorColorsManager.getGlobalScheme", _) => noColors
      }))
      // A key's own defaults, which code blocks in quick documentation fall back to: empty as well.
      appServices.register(classOf[com.intellij.openapi.editor.colors.TextAttributesKey.TextAttributeKeyDefaultsProvider])(
        _ => new com.intellij.openapi.editor.markup.TextAttributes())
      // Plugin attribution of logged platform errors (e.g. an annotation range outside its element); the host does not
      // track plugins, so the exception names none.
      appServices.register(classOf[com.intellij.diagnostic.PluginProblemReporter])(
        tableProxy(classOf[com.intellij.diagnostic.PluginProblemReporter], "PluginProblemReporter") {
          case ("createPluginExceptionByClass", Array(message: String, cause, _)) =>
            new com.intellij.diagnostic.PluginException(message, cause.asInstanceOf[Throwable], null)
        })
      // Usage statistics (FUS), which the plugin logs while highlighting: turned off. Building an event would create a
      // device id in the user's Java preferences, which the user's IntelliJ installations share. Every recorder gets
      // no providers, so event logging has nothing to log to and builds no event. The provider map is private platform
      // state; if it changes, this fails at startup.
      locally {
        type Providers = java.util.Collection[com.intellij.internal.statistic.eventLog.StatisticsEventLoggerProvider]
        val holder = new com.intellij.internal.statistic.eventLog.StatisticsEventLogProvidersHolder(
          kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(kotlin.coroutines.EmptyCoroutineContext.INSTANCE))
        val noProviders = new java.util.AbstractMap[String, Providers] {
          override def get(recorder: Any): Providers = java.util.List.of()
          override def entrySet(): java.util.Set[java.util.Map.Entry[String, Providers]] = java.util.Set.of()
        }
        val field = classOf[com.intellij.internal.statistic.eventLog.StatisticsEventLogProvidersHolder].getDeclaredField("eventLoggerProvidersExt")
        field.setAccessible(true)
        field.get(holder).asInstanceOf[java.util.concurrent.atomic.AtomicReference[java.util.Map[String, Providers]]].set(noProviders)
        appServices.register(classOf[com.intellij.internal.statistic.eventLog.StatisticsEventLogProvidersHolder])(holder)
      }
      // Write-safety checks of PSI change notifications: they only check the UI thread, which the host never runs on.
      appServices.register(classOf[com.intellij.openapi.application.TransactionGuard])(new com.intellij.openapi.application.TransactionGuardImpl)
      // Annotations carry their quick fixes, which the platform wraps as intentions when the annotation is created.
      // The batch executor applies textual mod commands to preview copies without the desktop executor.
      appServices.register(classOf[com.intellij.modcommand.ModCommandExecutor])(
        new com.intellij.lang.impl.modcommand.ModCommandBatchExecutorImpl)
      appServices.register(classOf[com.intellij.modcommand.ModCommandService])(new com.intellij.lang.impl.modcommand.ModCommandServiceImpl)
      // Interpolation references give completion its Scala prefix inside plain strings; file paths and URL providers need IDE navigation.
      appServices.register(classOf[com.intellij.psi.PsiReferenceService])(new com.intellij.psi.PsiReferenceServiceImpl)
      appServices.register(classOf[com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistry]) {
        val registry = new com.intellij.psi.impl.source.resolve.reference.ReferenceProvidersRegistryImpl
        val provider = newPrivate(Class.forName("org.jetbrains.plugins.scala.lang.references.InterpolatedStringReferenceProvider")
          .asSubclass(classOf[com.intellij.psi.PsiReferenceProvider]))
        registry.getRegistrar(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE).registerReferenceProvider(
          com.intellij.patterns.PlatformPatterns.psiElement(classOf[org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral]),
          provider, com.intellij.psi.PsiReferenceRegistrar.DEFAULT_PRIORITY, root)
        registry
      }
      // Host module metadata lives in ModuleModel; there are no IDE workspace entities or manifest overrides.
      projectServices.register(classOf[com.intellij.platform.backend.workspace.WorkspaceModel])(
        tableProxy(classOf[com.intellij.platform.backend.workspace.WorkspaceModel], "WorkspaceModel") {
          case ("getCurrentSnapshot", _) => com.intellij.platform.workspace.storage.ImmutableEntityStorage.Companion.empty()
        })
      projectServices.register(classOf[com.intellij.codeInsight.guess.GuessManager])(
        new com.intellij.codeInsight.guess.impl.GuessManagerImpl(project))
      projectServices.register(classOf[com.intellij.psi.controlFlow.ControlFlowFactory]) {
        val factory = new com.intellij.psi.controlFlow.ControlFlowFactory(project)
        Disposer.register(root, factory)
        factory
      }
      appServices.register(classOf[com.intellij.openapi.module.JdkApiCompatibilityService])(
        new com.intellij.openapi.module.JdkApiCompatibilityService)
      appServices.register(classOf[com.intellij.openapi.projectRoots.JavaVersionService])(
        new com.intellij.openapi.projectRoots.JavaVersionServiceImpl)
      // Java module (JPMS) accessibility: the host has no module-info graph (its modules come from the build), so the
      // platform's graph-less implementation, which treats every class as accessible.
      appServices.register(classOf[com.intellij.psi.JavaModuleGraphHelper])(new com.intellij.psi.DumbJavaModuleGraphHelper)
      // Platform library implementations used by Java PSI of library classes and by PSI copies.
      appServices.register(classOf[com.intellij.psi.util.JavaClassSupers])(new com.intellij.psi.impl.JavaClassSupersImpl)
      appServices.register(classOf[com.intellij.psi.PsiSubstitutorFactory])(new com.intellij.psi.impl.PsiSubstitutorFactoryImpl)
      appServices.register(classOf[com.intellij.psi.impl.file.PsiPackageImplementationHelper])(new com.intellij.core.CorePsiPackageImplementationHelper)
      appServices.register(classOf[com.intellij.psi.impl.source.codeStyle.IndentHelper])(new com.intellij.psi.impl.source.codeStyle.IndentHelperImpl)
    }

    /** Registers language implementations and library/index services after the root extension area exists. */
    private def installLanguageRegistries(): Unit = {
      /** Converts IntelliJ's wildcard query arguments to the host's typed word-index predicate. */
      def wordQuery(words: java.util.Collection[?], checker: Condition[? >: Integer]): WordQuery =
        WordQuery(words.asScala.map(_.asInstanceOf[IdIndexEntry]).toSeq, mask => checker == null || checker.value(mask))

      // ---- 2. Language registries and stub schema: explicit extensions only ---------------------
      val parserDefinitions: Seq[ParserDefinition] = Seq(
        new org.jetbrains.plugins.scala.lang.parser.ScalaParserDefinition,
        new org.jetbrains.plugins.scala.lang.parser.Scala3ParserDefinition,
        new org.jetbrains.plugins.scalaDoc.lang.parser.ScalaDocParserDefinition,
        new org.jetbrains.plugins.scalaDirective.lang.parser.ScalaDirectiveParserDefinition,
        // The contents of ScalaDoc links ([[scala.Option]]), parsed lazily inside doc comments.
        new org.jetbrains.plugins.scala.lang.scaladoc.reflinks.ScalaDocRefLinkParserDefinition,
      )
      parserDefinitions.foreach(pd => LanguageParserDefinitions.INSTANCE.addExplicitExtension(pd.getFileNodeType.getLanguage, pd))
      // Word selection uses the same layered lexer as a Scala editor, including string escapes and ScalaDoc.
      declare(SyntaxHighlighterLanguageFactory.EP_NAME.getName)
      SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE,
        new ScalaSyntaxHighlighterFactory)
      for ((language, highlighter) <- Seq[(Language, SyntaxHighlighter)](
        ScalaDocLanguage.INSTANCE -> new ScalaDocSyntaxHighlighter,
        ScalaDirectiveLanguage.INSTANCE -> new ScalaDirectiveSyntaxHighlighter,
        HTMLLanguage.INSTANCE -> new HtmlFileHighlighter)) {
        SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(language, new SyntaxHighlighterFactory {
          override def getSyntaxHighlighter(project: Project, file: VirtualFile): SyntaxHighlighter = highlighter
        })
      }
      SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(JavaLanguage.INSTANCE,
        new com.intellij.lang.java.JavaSyntaxHighlighterFactory)
      LanguageCommenters.INSTANCE.addExplicitExtension(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE, new ScalaCommenter)
      // The platform descriptor's default enables whitespace expansion. Other advanced settings remain unsupported.
      appServices.register(classOf[AdvancedSettings])(new AdvancedSettings {
        override protected def getSetting(id: String): AnyRef = id match {
          // Ordinary member completion; qualifier-as-argument rewrites are not enabled in the host.
          case "java.completion.qualifier.as.argument" => java.lang.Boolean.FALSE
          case "editor.selection.expand-whitespaces" =>
            CallLog.answered("AdvancedSettings.editor.selection.expand-whitespaces")
            java.lang.Boolean.TRUE
          case _ => CallLog.unsupported(s"AdvancedSettings.getSetting $id")
        }
        override protected def getDefault(id: String): AnyRef = CallLog.unsupported(s"AdvancedSettings.getDefault $id")
        override def setSetting(id: String, value: Any, kind: AdvancedSettingType): Unit = CallLog.unsupported(s"AdvancedSettings.setSetting $id")
      })
      // Parameter info for arguments, type arguments and patterns: the plugin's handlers, as its descriptors register them.
      for (bean <- Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.codeInsight.parameterInfo")
           if bean.attributes.get("language").contains("Scala")) {
        val handler = newPrivate(Class.forName(bean.implementation)).asInstanceOf[ParameterInfoHandler[?, ?]]
        LanguageParameterInfo.INSTANCE.addExplicitExtension(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE, handler)
      }
      // beforeCompletion and lookup escaping must use Scala's operators and keywords, rather than the Java fallback.
      com.intellij.lang.LanguageNamesValidation.INSTANCE.addExplicitExtension(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE,
        new org.jetbrains.plugins.scala.lang.refactoring.ScalaNamesValidator)
      // Java sources of mixed projects.
      LanguageParserDefinitions.INSTANCE.addExplicitExtension(com.intellij.lang.java.JavaLanguage.INSTANCE, new com.intellij.lang.java.JavaParserDefinition)
      // Java's typed leaves (PsiIdentifier, PsiKeyword, ...): Java PSI casts its tokens to them.
      LanguageASTFactory.INSTANCE.addExplicitExtension(com.intellij.lang.java.JavaLanguage.INSTANCE, new com.intellij.psi.impl.source.tree.JavaASTFactory)
      // The Scala filter requires a Java provider. Java's real provider fails to construct without JavaBundle;
      // its default exclusions are unavailable, so Java calls can show names the IDE would exclude.
      InlayParameterHintsExtension.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new InlayParameterHintsProvider {
        override def getDefaultBlackList: JavaSet[String] = JavaSet.of()
      })
      // Java classes and fields describe themselves through these (getPresentation): completion shows a library class
      // after `new` with its package.
      ItemPresentationProviders.INSTANCE.addExplicitExtension(classOf[PsiClass], new ClassPresentationProvider)
      ItemPresentationProviders.INSTANCE.addExplicitExtension(classOf[PsiField], new FieldPresentationProvider)
      com.intellij.platform.syntax.psi.LanguageSyntaxDefinitions.getINSTANCE.addExplicitExtension(
        com.intellij.lang.java.JavaLanguage.INSTANCE, new com.intellij.java.frontback.psi.impl.syntax.JavaSyntaxDefinitionExtension)
      com.intellij.platform.syntax.psi.ElementTypeConverters.getInstance.addExplicitExtension(
        com.intellij.lang.java.JavaLanguage.INSTANCE, new com.intellij.lang.java.syntax.JavaElementTypeConverterExtension)
      // Tokens every language shares (whitespace, bad characters): the converters of a language are all those
      // registered for it, so the common one is registered for Java too.
      com.intellij.platform.syntax.psi.ElementTypeConverters.getInstance.addExplicitExtension(
        com.intellij.lang.java.JavaLanguage.INSTANCE, new com.intellij.platform.syntax.psi.CommonElementTypeConverterFactory)
      val scalaLang = org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE
      val scala3Lang = org.jetbrains.plugins.scala.Scala3Language.INSTANCE
      LanguageASTFactory.INSTANCE.addExplicitExtension(scalaLang, new org.jetbrains.plugins.scala.lang.parser.ScalaASTFactory)
      // A .scala file of a Scala 3 module is Scala 3 (the plugin's substitutor reads the file's module).
      com.intellij.psi.LanguageSubstitutors.getInstance.addExplicitExtension(scalaLang, new org.jetbrains.plugins.scala.lang.parser.ScalaLanguageSubstitutor)
      val viewProviderFactory = new org.jetbrains.plugins.scala.lang.psi.ScFileViewProviderFactory
      LanguageFileViewProviders.INSTANCE.addExplicitExtension(scalaLang, viewProviderFactory)
      LanguageFileViewProviders.INSTANCE.addExplicitExtension(scala3Lang, viewProviderFactory)

      // Stub schema (model): the plugin's serializer/factory registrations and language stub definitions.
      val scalaPlugin = new DefaultPluginDescriptor(PluginId.getId("org.intellij.scala"))
      /** Associates a language stub definition with the Scala plugin that owns its extension. */
      def stubDefinition(language: String, definition: LanguageStubDefinition): LanguageExtensionPoint[LanguageStubDefinition] = {
        val bean = new LanguageExtensionPoint[LanguageStubDefinition](language, definition)
        bean.setPluginDescriptor(scalaPlugin)
        bean
      }

      val declaredStubRegistry = declare("com.intellij.stubElementRegistryExtension").asInstanceOf[ExtensionPointImpl[StubRegistryExtension]]
      declaredStubRegistry
        .registerExtension(new org.jetbrains.plugins.scala.lang.psi.stubs.ScalaStubRegistryExtension, root)
      declaredStubRegistry.registerExtension(new com.intellij.psi.impl.java.stubs.JavaStubRegistryExtension, root)
      // Element type <-> PSI class mapping the Java stub registry fills (private constructor, as a container service).
      appServices.register(classOf[com.intellij.psi.impl.java.stubs.JavaStubElementTypePsiElementMappingRegistry])(
        newPrivate(classOf[com.intellij.psi.impl.java.stubs.JavaStubElementTypePsiElementMappingRegistry]))
      val stubDefinitions = declare("com.intellij.languageStubDefinition")
        .asInstanceOf[ExtensionPointImpl[LanguageExtensionPoint[LanguageStubDefinition]]]
      stubDefinitions.registerExtension(stubDefinition("Scala", new org.jetbrains.plugins.scala.lang.psi.stubs.ScalaLanguageStubDefinition), scalaPlugin, root)
      stubDefinitions.registerExtension(stubDefinition("Scala 3", new org.jetbrains.plugins.scala.lang.psi.stubs.Scala3LanguageStubDefinition), scalaPlugin, root)
      stubDefinitions.registerExtension(stubDefinition(com.intellij.lang.java.JavaLanguage.INSTANCE.getID, new com.intellij.psi.impl.java.stubs.JavaStubDefinition), scalaPlugin, root)
      appServices.register(classOf[StubElementRegistryService])(
        new com.intellij.psi.stubs.StubElementRegistryServiceImpl(kotlinx.coroutines.CoroutineScopeKt.CoroutineScope(kotlin.coroutines.EmptyCoroutineContext.INSTANCE)))

      // Compiler statistics need IDE-built indexes; live argument templates need an interactive editor.
      val javaRegistryKeys = Seq(
        "compiler.ref.index" -> "Compiler reference index",
        "ide.java.completion.suggest.static.after.instance" -> "Suggest static members after instance qualifiers",
        "java.completion.argument.live.template" -> "Interactive argument templates",
        "java.completion.show.constructors" -> "Separate constructor suggestions",
      ).map { (key, description) => key -> new com.intellij.openapi.util.registry.RegistryKeyDescriptor(
        key, description, "false", false, false, "com.intellij.java", null) }.toMap
      val highestJava = "java.highest.language.level"
      val javaRelease = new com.intellij.openapi.util.registry.RegistryKeyDescriptor(highestJava,
        "Highest Java level supported by the host", com.intellij.pom.java.LanguageLevel.HIGHEST.toJavaVersion.feature.toString,
        false, false, "com.intellij.java", null)
      Registry.Companion.setContributedKeys((Descriptors.scalaRegistryKeys(getClass.getClassLoader) ++ javaRegistryKeys +
        (highestJava -> javaRelease)).asJava)
      Registry.Companion.markAsLoaded()

      val fileTypes = new CoreFileTypeRegistry
      fileTypes.registerFileType(org.jetbrains.plugins.scala.ScalaFileType.INSTANCE, "scala")
      fileTypes.registerFileType(com.intellij.ide.highlighter.JavaFileType.INSTANCE, "java")
      fileTypes.registerFileType(com.intellij.ide.highlighter.JavaClassFileType.INSTANCE, "class")
      // Scala 3 library classes: the .tasty file is decompiled into Scala 3 PSI, its .class sibling is hidden.
      fileTypes.registerFileType(org.jetbrains.plugins.scala.tasty.TastyFileType, "tasty")
      // View providers of binary library files, by file type, as the IDE registers them.
      com.intellij.psi.FileTypeFileViewProviders.INSTANCE.addExplicitExtension(
        com.intellij.ide.highlighter.JavaClassFileType.INSTANCE, new com.intellij.psi.ClassFileViewProviderFactory)
      com.intellij.psi.FileTypeFileViewProviders.INSTANCE.addExplicitExtension(
        org.jetbrains.plugins.scala.tasty.TastyFileType, new org.jetbrains.plugins.scala.tasty.TastyFileViewProviderFactory)
      // The registry is also the FileTypeManager: FileTypeManager.getInstance falls back to an empty manager (which
      // answers a mock file type for everything) when the registry is not one. Lookups go to the core registry above.
      val fileTypeManager = new Host_FileTypeManager(dispatch {
        case ("FileTypeManager.getFileTypeByFileName", Array(name: String)) => fileTypes.getFileTypeByFileName(name)
        case ("FileTypeManager.getFileTypeByExtension", Array(extension: String)) => fileTypes.getFileTypeByExtension(extension)
        case ("FileTypeManager.getFileTypeByFile", Array(f: VirtualFile)) => fileTypes.getFileTypeByFile(f)
        case ("FileTypeManager.findFileTypeByName", Array(name: String)) => fileTypes.findFileTypeByName(name)
        case ("FileTypeManager.getRegisteredFileTypes", _) => fileTypes.getRegisteredFileTypes
        // Files come from the build's source roots and library jars; none is ignored.
        case ("FileTypeManager.isFileIgnored", _) => java.lang.Boolean.FALSE
      })
      FileTypeRegistry.setInstanceSupplier(() => fileTypeManager, root)

      // Class files become PSI as in the IDE: the first decompiler that accepts the file wins; the plugin's comes
      // before the platform's Java decompiler.
      val decompilers = declare("com.intellij.psi.classFileDecompiler")
        .asInstanceOf[ExtensionPointImpl[com.intellij.psi.compiled.ClassFileDecompilers.Decompiler]]
      decompilers.registerExtension(new org.jetbrains.plugins.scala.lang.psi.compiled.ScClassFileDecompiler, root)
      decompilers.registerExtension(new com.intellij.psi.impl.compiled.ClsDecompilerImpl, root)
      appServices.register(classOf[com.intellij.psi.compiled.ClassFileDecompilers])(newPrivate(classOf[com.intellij.psi.compiled.ClassFileDecompilers]))
      // Stubs of library class files are built on demand from their bytes (the core loader kotlinc uses), through the
      // platform's class-file stub builder, which defers to the same decompilers.
      com.intellij.psi.stubs.BinaryFileStubBuilders.INSTANCE.addExplicitExtension(
        org.jetbrains.plugins.scala.tasty.TastyFileType, new org.jetbrains.plugins.scala.tasty.TastyFileStubBuilder)
      com.intellij.psi.stubs.BinaryFileStubBuilders.INSTANCE.addExplicitExtension(
        com.intellij.ide.highlighter.JavaClassFileType.INSTANCE, new com.intellij.psi.impl.compiled.ClassFileStubBuilder)
      appServices.register(classOf[com.intellij.psi.stubs.StubTreeLoader])(new com.intellij.psi.stubs.CoreStubTreeLoader)
      // A binary library file's text (its document, which Java class files' PSI mirrors): decompiled as the IDE
      // registers it, Java class files by the platform's decompiler, TASTy files by the plugin's.
      declare("com.intellij.filetype.decompiler")
      appServices.register(classOf[com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers])(
        newPrivate(classOf[com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers]))
      com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers.getInstance.addExplicitExtension(
        com.intellij.ide.highlighter.JavaClassFileType.INSTANCE, new com.intellij.psi.impl.compiled.ClassFileDecompiler)
      com.intellij.openapi.fileTypes.BinaryFileTypeDecompilers.getInstance.addExplicitExtension(
        org.jetbrains.plugins.scala.tasty.TastyFileType, new org.jetbrains.plugins.scala.tasty.TastyDecompiler)
      // File properties the IDE pushes to files (language levels), which stub builders read to version their stubs; the
      // host pushes none.
      appServices.register(classOf[com.intellij.openapi.roots.impl.PushedFilePropertiesRetriever])(
        tableProxy(classOf[com.intellij.openapi.roots.impl.PushedFilePropertiesRetriever], "PushedFilePropertiesRetriever") {
          case ("dumpSortedPushedProperties", _) => java.util.List.of()
        })
      val libraryFiles = this.libraryFiles
      // Class root → its attached source root.
      val librarySources = this.librarySources
      // The platform's file-based indexes: the file-name index, answered from library roots, and the word index of
      // project sources, which the reference search reads to find the files that mention a name.
      appServices.register(classOf[com.intellij.util.indexing.FileBasedIndex])(new Host_FileBasedIndex(dispatch {
        case ("FileBasedIndex.getValues", Array(id, qualifier: String, scope: GlobalSearchScope))
            if id == org.jetbrains.plugins.scala.autoImport.ImportOrderingIndexer.Id =>
          importIndex.values(qualifier, scope)
        case ("FileBasedIndex.processFilesContainingAnyKey",
              Array(id: com.intellij.util.indexing.ID[?, ?], names: java.util.Collection[?], scope: com.intellij.psi.search.GlobalSearchScope, _, _,
                    processor: com.intellij.util.Processor[?])) if id == com.intellij.psi.search.FilenameIndex.NAME =>
          val p = processor.asInstanceOf[com.intellij.util.Processor[VirtualFile]]
          val matching = names.asScala.toSeq.flatMap(n => libraryFiles.filesNamed(n.toString)).filter(scope.contains)
          java.lang.Boolean.valueOf(matching.forall(p.process))
        case ("FileBasedIndex.getContainingFiles", Array(id, word: IdIndexEntry, scope: GlobalSearchScope)) if id == IdIndex.NAME =>
          wordIndex.filesWith(word, scope).asJava
        case ("FileBasedIndex.processValues", Array(id, word: IdIndexEntry, inFile, processor: FileBasedIndex.ValueProcessor[?], scope: GlobalSearchScope))
            if id == IdIndex.NAME =>
          val p = processor.asInstanceOf[FileBasedIndex.ValueProcessor[Integer]]
          val files = inFile match {
            case file: VirtualFile => Seq(file).filter(scope.contains)
            case _ => wordIndex.filesWith(word, scope)
          }
          java.lang.Boolean.valueOf(files.forall(file => wordIndex.occurrences(word, file).forall(mask => p.process(file, mask))))
        case ("FileBasedIndex.processFilesContainingAllKeys",
              Array(id, words: java.util.Collection[?], scope: GlobalSearchScope, checker, processor: Processor[?])) if id == IdIndex.NAME =>
          val p = processor.asInstanceOf[Processor[VirtualFile]]
          java.lang.Boolean.valueOf(wordIndex.filesWithAll(Seq(wordQuery(words, checker.asInstanceOf[Condition[Integer]])), scope).forall(p.process))
        // The reference search asks for several indexes at once; the host has the word index only.
        case ("FileBasedIndex.processFilesContainingAllKeys", Array(queries: java.util.Collection[?], scope: GlobalSearchScope, processor: Processor[?])) =>
          val p = processor.asInstanceOf[Processor[VirtualFile]]
          val wordQueries = queries.asScala.toSeq.map {
            case query: FileBasedIndex.AllKeysQuery[?, ?] if query.getIndexId == IdIndex.NAME =>
              wordQuery(query.getDataKeys, query.getValueChecker.asInstanceOf[Condition[Integer]])
            case other => CallLog.unsupported(s"FileBasedIndex.processFilesContainingAllKeys over the index of $other")
          }
          java.lang.Boolean.valueOf(wordIndex.filesWithAll(wordQueries, scope).forall(p.process))
      }))
      // How files are split into words for that index: by language (the plugin's and Java's words scanners; Java's has
      // its own indexer), and no file type outside a language has one.
      appServices.register(classOf[com.intellij.lang.cacheBuilder.CacheBuilderRegistry])(new com.intellij.lang.cacheBuilder.CacheBuilderRegistry {
        override def getCacheBuilder(fileType: com.intellij.openapi.fileTypes.FileType): com.intellij.lang.cacheBuilder.WordsScanner = null
      })
      com.intellij.lang.findUsages.LanguageFindUsages.INSTANCE.addExplicitExtension(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE,
        new org.jetbrains.plugins.scala.lang.findUsages.ScalaFindUsagesProvider)
      com.intellij.lang.findUsages.LanguageFindUsages.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new com.intellij.lang.java.JavaFindUsagesProvider)
      com.intellij.psi.impl.cache.impl.id.IdIndexers.INSTANCE.addExplicitExtension(com.intellij.ide.highlighter.JavaFileType.INSTANCE,
        new com.intellij.psi.impl.cache.impl.idCache.JavaIdIndexer)
      // Find usages searches the project scope by default; the host offers no other predefined scopes.
      appServices.register(classOf[com.intellij.find.FindUsagesSettings])(new com.intellij.find.impl.FindUsagesSettingsImpl)
      appServices.register(classOf[com.intellij.psi.search.PredefinedSearchScopeProvider])(new com.intellij.psi.search.PredefinedSearchScopeProvider {
        override def getPredefinedScopes(project: Project, dataContext: com.intellij.openapi.actionSystem.DataContext, suggestSearchInLibs: Boolean,
                                         prevSearchFiles: Boolean, currentSelection: Boolean, usageView: Boolean, showEmptyScopes: Boolean) =
          java.util.List.of()
      })
      // The element a position stands for, as the editor finds it: the plugin's evaluator decides for Scala.
      declare("com.intellij.targetElementUtilExtender")
      appServices.register(classOf[com.intellij.codeInsight.TargetElementUtil])(new com.intellij.codeInsight.TargetElementUtil)
      com.intellij.codeInsight.TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR.addExplicitExtension(org.jetbrains.plugins.scala.ScalaLanguage.INSTANCE,
        new org.jetbrains.plugins.scala.codeInsight.ScalaTargetElementEvaluator)
      // Extension points the reference search and the go-to-class contributor read, with no extensions in the host.
      for (name <- Seq("com.intellij.useScopeOptimizer", "com.intellij.codeUsageScopeOptimizer", "com.intellij.searchCandidateBatcher",
                       "com.intellij.pom.declarationSearcher", "com.intellij.codeInsight.containerProvider",
                       "com.intellij.gotoByName.defaultProvider.ignoreLanguage")) declare(name)
      // Go to implementation: the platform's searches of a class's inheritors and a method's overriders, which also ask for
      // functional expressions (lambdas, method references) of the Java plugin's search, with no searcher in the host.
      declare("com.intellij.functionalExpressionSearch")
      declare("com.intellij.definitionsScopedSearch")
      locally {
        val searchers = area.getExtensionPoint[com.intellij.util.QueryExecutor[com.intellij.psi.PsiElement, com.intellij.psi.search.searches.DefinitionsScopedSearch.SearchParameters]](
          "com.intellij.definitionsScopedSearch")
        searchers.registerExtension(new com.intellij.codeInsight.navigation.ClassImplementationsSearch, root)
        searchers.registerExtension(new com.intellij.codeInsight.navigation.MethodImplementationsSearch, root)
      }
      // The platform's reference searcher: it scans the files the word index names for an element's name. (The IDE's
      // SearcherQueryExecutor, for searches by symbol, is left out: no Scala element is one.)
      area.getExtensionPoint[com.intellij.util.QueryExecutor[com.intellij.psi.PsiReference, com.intellij.psi.search.searches.ReferencesSearch.SearchParameters]](
        "com.intellij.referencesSearch").registerExtension(new com.intellij.psi.impl.search.CachesBasedRefSearcher, root)
      // The search helper's file scans run one after the other, as in the platform's core environment: requests already
      // hold the host's read lock.
      appServices.register(classOf[com.intellij.concurrency.JobLauncher])(new com.intellij.core.CoreJobLauncher)
      // The host is never in dumb mode: indexes are usable.
      appServices.register(classOf[com.intellij.openapi.project.DumbUtil])(
        tableProxy(classOf[com.intellij.openapi.project.DumbUtil], "DumbUtil") {
          case ("mayUseIndices", _) => java.lang.Boolean.TRUE
        })
      // Persistent file attributes: the plugin caches "is this class file Scala?" in them. The host persists nothing,
      // so every read misses (the plugin recomputes) and writes are discarded.
      appServices.register(classOf[com.intellij.openapi.vfs.newvfs.ManagingFS])(new Host_ManagingFS(dispatch {
        case ("ManagingFS.readAttribute", _) => null
        case ("ManagingFS.writeAttribute", _) =>
          new com.intellij.openapi.vfs.newvfs.AttributeOutputStream(new com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream()) {
            override def writeEnumeratedString(value: String): Unit = writeUTF(value)
          }
      }))
    }

    /** Registers the project object and its PSI, indexing, search, and module services. */
    private def installProjectServices(): Unit = {
      /** Resolves a PSI element's original containing file, including synthetic and stub-backed elements. */
      def contextFile(element: PsiElement): Option[VirtualFile] =
        Option(FileContextUtil.getContextFile(element)).map(_.getOriginalFile.getViewProvider.getVirtualFile)

      // ---- 3. Project object -----------------------------------------------------------------
      // The platform's modification tracker: it counts the tree changes edits produce (see HostPsiManager).
      // The host's file store: view providers of project sources and of library files, keyed by virtual file; sources
      // register themselves, library files are created on first request. Other light files are the platform's
      // temporary copies (a reparse makes one per edit): such a file holds its own provider, as in the platform's
      // LightFileViewProviderCache, so the copy is collected once no one holds it.
      var psiManagerRef: PsiManager = null
      val fileStore = new HostFileStore(project, () => psiManagerRef)
      val fileManager = fileStore.adapter
      // Library files (classes and attached sources) live in the host's library file system; every other file is a
      // project source the host was given.
      val fileIndex = tableProxy(classOf[com.intellij.openapi.roots.ProjectFileIndex], "ProjectFileIndex") {
        case ("getClassRootForFile", Array(f: LibraryFile)) if !f.isLibrarySource => f.root
        case ("getClassRootForFile", _) => null
        case ("getSourceRootForFile", Array(f: LibraryFile)) if f.isLibrarySource => f.root
        case ("getSourceRootForFile", _) => null
        case ("isInProject" | "isInProjectOrExcluded", Array(f: VirtualFile)) =>
          java.lang.Boolean.valueOf(moduleModel.ofFile(f).nonEmpty || fileStore.isLibrary(f))
        case ("isInLibrary", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(fileStore.isLibrary(f))
        case ("isInLibraryClasses", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(fileStore.isLibraryClass(f))
        case ("isInLibrarySource", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(fileStore.isLibrarySource(f))
        // The host has no workspace model: no library or SDK entity contains a file.
        case ("findContainingLibraries" | "findContainingSdks", _) => java.util.List.of()
        // Files opened in a module; other files (light files, libraries) have none.
        case ("getModuleForFile", Array(f: VirtualFile, _*)) => moduleModel.ofFile(f).map(_.module).orNull
        case ("isInSourceContent" | "isInContent", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(!fileStore.isLibrary(f))
        // Sources: the project's and the libraries' attached ones.
        case ("isInSource", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(!fileStore.isLibraryClass(f))
        case ("isInTestSourceContent", _) => java.lang.Boolean.FALSE
        // Every source file the host was given is a production source; the plugin's filter scopes ask by root type.
        case ("isUnderSourceRootOfType", Array(f: VirtualFile, types: java.util.Set[?])) =>
          java.lang.Boolean.valueOf(!fileStore.isLibrary(f) && types.contains(org.jetbrains.jps.model.java.JavaSourceRootType.SOURCE))
      }
      projectServices.register(classOf[com.intellij.openapi.roots.ProjectFileIndex])(fileIndex)
      // Java stub eligibility reads a persisted level as the source-content marker. The host owns source membership,
      // and uses the same highest Java level here as in JavaPsiImplementationHelper, without persisting VFS attributes.
      appServices.register(classOf[com.intellij.pom.java.InternalPersistentJavaLanguageLevelReaderService])(
        tableProxy(classOf[com.intellij.pom.java.InternalPersistentJavaLanguageLevelReaderService], "JavaLanguageLevelReader") {
          case ("getPersistedLanguageLevel", Array(f: VirtualFile)) =>
            if (moduleModel.ofFile(f).isDefined) com.intellij.pom.java.LanguageLevel.HIGHEST else null
        })
      // The same answers through the platform's facade (ClassFileViewProvider asks it before building Java PSI).
      projectServices.register(classOf[com.intellij.openapi.roots.FileIndexFacade])(new Host_FileIndexFacade(project, dispatch {
        case ("FileIndexFacade.isInLibraryClasses", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(fileStore.isLibraryClass(f))
        case ("FileIndexFacade.isInLibrary", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(fileStore.isLibrary(f))
        case ("FileIndexFacade.isInLibrarySource", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(fileStore.isLibrarySource(f))
        // Roots change only when a library or the JDK is mounted, which also counts as a PSI change.
        case ("FileIndexFacade.getRootModificationTracker", _) => psiModTracker
        case ("FileIndexFacade.getModuleForFile", Array(f: VirtualFile)) => moduleModel.ofFile(f).map(_.module).orNull
        case ("FileIndexFacade.isInSourceContent" | "FileIndexFacade.isInContent", Array(f: VirtualFile)) =>
          java.lang.Boolean.valueOf(!fileStore.isLibrary(f))
        case ("FileIndexFacade.isInSource", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(!fileStore.isLibraryClass(f))
      }))
      // Tree changes reach the plugin's listener, which decides which of its caches an edit invalidates.
      val psiManager = new HostPsiManager(project, psiModTracker, fileManager, psiModTracker,
        java.util.List.of(new org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiChangeListener(project)))
      psiManagerRef = psiManager
      projectServices.register(classOf[PsiManager])(psiManager)
      projectServices.register(classOf[PsiModificationTracker])(psiModTracker)
      // Host root changes already invalidate PSI; using that count also invalidates Java module-descriptor caches.
      projectServices.register(classOf[com.intellij.openapi.roots.ProjectRootModificationTracker])(
        new com.intellij.openapi.roots.ProjectRootModificationTracker {
          override def getModificationCount: Long = psiModTracker.getModificationCount
        })
      projectServices.register(classOf[com.intellij.psi.impl.PsiJavaModuleModificationTracker]) {
        val tracker = new com.intellij.psi.impl.PsiJavaModuleModificationTracker(project)
        Disposer.register(root, tracker)
        tracker
      }
      // PSI <-> document synchronization without the IDE's global editor event multicaster.
      projectServices.register(classOf[com.intellij.psi.PsiDocumentManager]) {
        val documents = new HostPsiDocumentManager(project)
        // Commits are explicit and synchronous (ScalaPsiHost.edit), never the IDE's background commit after typing.
        documents.disableBackgroundCommit(root)
        documents
      }
      // Platform library implementations, constructed by the host (no container wiring).
      // Local unused-symbol analysis asks the profile only for the native entry-point policy.
      // Keep that policy at platform defaults; this host does not import or persist desktop profiles.
      val entryPoints = new com.intellij.codeInspection.ex.EntryPointsManagerBase(project) {
        override def configureAnnotations(): Unit = CallLog.unsupported("EntryPointsManager.configureAnnotations")
      }
      Disposer.register(root, entryPoints)
      projectServices.register(classOf[com.intellij.codeInspection.ex.EntryPointsManager])(entryPoints)
      projectServices.register(classOf[com.intellij.profile.codeInspection.InspectionProjectProfileManager]) {
        val profiles = new HostInspectionProfileManager(projectBus)
        Disposer.register(root, profiles)
        profiles
      }
      // The plugin's unused-declaration inspection owns the local reference-search pipeline and its PSI caches.
      locally {
        val cls = Class.forName("org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.cheapRefSearch.SearchMethodsWithProjectBoundCache")
          .asInstanceOf[Class[AnyRef]]
        projectServices.register(cls)(newPrivate(cls, project))
      }
      projectServices.register(classOf[com.intellij.psi.util.CachedValuesManager])(
        new com.intellij.util.CachedValuesManagerImpl(project, new com.intellij.psi.impl.PsiCachedValuesFactory(project)))
      projectServices.register(classOf[com.intellij.psi.PsiFileFactory])(new com.intellij.psi.impl.PsiFileFactoryImpl(psiManager))
      projectServices.register(classOf[com.intellij.openapi.roots.ProjectRootManager])(new Host_ProjectRootManager(dispatch {
        case ("ProjectRootManager.getFileIndex", _) => fileIndex
        // JDK roots are mounted directly; the host has no configured IntelliJ SDK entity.
        case ("ProjectRootManager.getProjectSdk", _) => null
        // Each host module carries its root manager (see ScalaPsiHost.newModule).
        case ("ProjectRootManager.getModuleRootManager", Array(m: Module)) =>
          m.getService(classOf[com.intellij.openapi.roots.ModuleRootManager])
      }))
      // The host's indexes are always ready before it answers queries.
      projectServices.register(classOf[DumbService])(new Host_DumbService(dispatch {
        case ("DumbService.isDumb", _) => java.lang.Boolean.FALSE
        // The host never enters dumb mode, so this state has no transitions to invalidate.
        case ("DumbService.getModificationTracker", _) => com.intellij.openapi.util.ModificationTracker.NEVER_CHANGED
        // Index-free "alternative" resolution exists only for dumb mode, which the host never enters.
        case ("DumbService.isAlternativeResolveEnabled", _) => java.lang.Boolean.FALSE
        // No extension is dumb-aware-filtered away: the host is never dumb.
        case ("DumbService.filterByDumbAwareness", Array(c: java.util.Collection[?])) => java.util.List.copyOf(c)
        case ("DumbService.getProject", _) => project
        // Dumb-mode tasks (synthetic class registration) run to completion on the caller's thread.
        case ("DumbService.queueTask", Array(task: DumbModeTask)) =>
          try task.performInDumbMode(new EmptyProgressIndicator(ModalityState.nonModal()))
          finally Disposer.dispose(task)
          null
      }))
      // The same holds for dependencies: libraries are indexed before the host answers, so highlighting is type-aware.
      projectServices.register(classOf[com.intellij.openapi.project.IncompleteDependenciesService])(
        tableProxy(classOf[com.intellij.openapi.project.IncompleteDependenciesService], "IncompleteDependenciesService") {
          case ("getState" | "getStateUnsafe", _) => com.intellij.openapi.project.IncompleteDependenciesService.DependenciesState.COMPLETE
        })
      // Every file gets full highlighting ("All Problems"); the IDE's per-file level is a UI setting.
      projectServices.register(classOf[com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager])(new Host_HighlightingLevelManager(dispatch {
        case ("HighlightingLevelManager.shouldHighlight" | "HighlightingLevelManager.shouldInspect", _) => java.lang.Boolean.TRUE
        case ("HighlightingLevelManager.runEssentialHighlightingOnly", _) => java.lang.Boolean.FALSE
      }))
      projectServices.register(classOf[com.intellij.openapi.module.ModuleManager])(new Host_ModuleManager(dispatch {
        case ("ModuleManager.getModules", _) => moduleModel.all.map(_.module).toArray
        case ("ModuleManager.findModuleByName", Array(name: String)) => moduleModel.byId(name).map(_.module).orNull
      }))
      // Compiler settings per module, configured from the build's scalac options as the plugin's importers do.
      projectServices.register(classOf[org.jetbrains.plugins.scala.project.settings.ScalaCompilerConfiguration])(
        new org.jetbrains.plugins.scala.project.settings.ScalaCompilerConfiguration(project))
      // The compiler plugins the plugin supports, where a module's scalac options enable them: kind-projector's syntax
      // (`λ`, `*`, `?`) and better-monadic-for's desugaring of for-comprehensions.
      projectServices.register(classOf[KindProjectorUtil])(new KindProjectorUtil(project))
      projectServices.register(classOf[BetterMonadicForSupport])(new BetterMonadicForSupport(project))
      // PSI write path: the plugin edits non-physical copies (e.g. for-comprehension desugaring).
      projectServices.register(classOf[com.intellij.pom.PomModel])(
        classOf[com.intellij.pom.PomModel].cast(newPrivate(
          Class.forName("com.intellij.psi.impl.source.PostprocessReformattingAspectImpl$LangPomModel"), project)))
      projectServices.register(classOf[com.intellij.pom.tree.TreeAspect])(new com.intellij.pom.tree.TreeAspect)
      projectServices.register(classOf[com.intellij.psi.SmartPointerManager])(new com.intellij.psi.impl.smartPointers.SmartPointerManagerImpl(project))
      projectServices.register(classOf[com.intellij.psi.PsiElementFactory])(new com.intellij.psi.impl.PsiElementFactoryImpl(project))
      projectServices.register(classOf[com.intellij.psi.PsiNameHelper])(com.intellij.psi.impl.PsiNameHelperImpl.getInstance())
      // One scope for everything, as one scope per module: the plugin keys its caches by scope, so a new object per
      // request would add entries on every lookup that are never found again.
      val everythingScope = new com.intellij.psi.search.EverythingGlobalScope(project)
      // Packages are declared in the files' text, not by directories (the host's files have none): the index holds no
      // directory, and lists the files that declare a package, which the package scope of a package-private member needs.
      projectServices.register(classOf[com.intellij.openapi.roots.PackageIndex])(new com.intellij.openapi.roots.PackageIndex {
        override def getDirsByPackageName(packageName: String, includeLibrarySources: Boolean): com.intellij.util.Query[VirtualFile] =
          com.intellij.util.EmptyQuery.getEmptyQuery
        override def getFilesByPackageName(packageName: String): com.intellij.util.Query[VirtualFile] = {
          val declaring = moduleModel.all.flatMap(_.sources.asScala).filter { source =>
            PsiManager.getInstance(project).findFile(source) match {
              case owner: com.intellij.psi.PsiClassOwner => owner.getPackageName == packageName
              case _ => false
            }
          }
          new com.intellij.util.CollectionQuery(declaring.asJava)
        }
        override def getPackageNameByDirectory(dir: VirtualFile): String = null
      })
      projectServices.register(classOf[com.intellij.psi.impl.source.resolve.JavaResolveCache])(
        new com.intellij.psi.impl.source.resolve.JavaResolveCache(project))
      // The project's Java language level, which a class's declarations are processed at (the highest, as the files' own).
      projectServices.register(classOf[com.intellij.openapi.roots.LanguageLevelProjectExtension])(new com.intellij.openapi.roots.LanguageLevelProjectExtension {
        override def getLanguageLevel: com.intellij.pom.java.LanguageLevel = com.intellij.pom.java.LanguageLevel.HIGHEST
        override def setLanguageLevel(level: com.intellij.pom.java.LanguageLevel): Unit = CallLog.unsupported("LanguageLevelProjectExtension.setLanguageLevel")
        override def getDefault: java.lang.Boolean = java.lang.Boolean.TRUE
        override def setDefault(value: java.lang.Boolean): Unit = CallLog.unsupported("LanguageLevelProjectExtension.setDefault")
      })
      projectServices.register(classOf[com.intellij.psi.search.ProjectScopeBuilder])(new Host_ProjectScopeBuilder(dispatch {
        case ("ProjectScopeBuilder.buildProjectScope" | "ProjectScopeBuilder.buildContentScope", _) => moduleModel.sourcesScope
        case ("ProjectScopeBuilder.buildLibrariesScope" | "ProjectScopeBuilder.buildLibraryClassesScope", _) => moduleModel.librariesScope
        case ("ProjectScopeBuilder.buildAllScope" | "ProjectScopeBuilder.buildEverythingScope", _) => everythingScope
      }))
      // Class and package lookup: the platform's JavaPsiFacadeImpl over element finders, as in the IDE. The plugin's
      // finders come with the host's LibraryClassFinder, which takes the place of the IDE's PsiElementFinderImpl.
      projectServices.register(classOf[com.intellij.psi.JvmPsiConversionHelper])(new com.intellij.psi.impl.JvmPsiConversionHelperImpl)
      projectServices.register(classOf[com.intellij.psi.impl.source.resolve.ResolveCache])(new com.intellij.psi.impl.source.resolve.ResolveCache(project))
      projectServices.register(classOf[com.intellij.psi.impl.migration.PsiMigrationManager])(new com.intellij.psi.impl.migration.PsiMigrationManager(project))
      // External (XML) annotations of library classes: the host attaches none.
      projectServices.register(classOf[com.intellij.codeInsight.ExternalAnnotationsManager])(new Host_ExternalAnnotationsManager(dispatch {
        case ("ExternalAnnotationsManager.findExternalAnnotationsFiles", _) => null
        case ("ExternalAnnotationsManager.findExternalAnnotations", Array(_)) => com.intellij.psi.PsiAnnotation.EMPTY_ARRAY
        case ("ExternalAnnotationsManager.findExternalAnnotations", Array(_, _)) => java.util.List.of()
      }))
      projectServices.register(classOf[com.intellij.codeInsight.InferredAnnotationsManager])(new HostInferredAnnotationsManager)
      // Nullability annotations of library Java types: the host configures none (the IDE's defaults come from settings).
      projectServices.register(classOf[com.intellij.codeInsight.NullableNotNullManager])(new Host_NullableNotNullManager(project, dispatch {
        case ("NullableNotNullManager.getNullityDefault", _) => com.intellij.codeInsight.ContextNullabilityInfo.EMPTY
        case ("NullableNotNullManager.findEffectiveNullabilityInfo", _) => null
        // Asked of a type's annotations while substituting generics (the plugin's isInheritor on a class file).
        case ("NullableNotNullManager.getAnnotationNullability", _) => java.util.Optional.empty()
      }))
      // Java class files navigate to their sources through this helper, as the IDE's: the source file of the same
      // package and name in the sources attached to the class's library, or the class file itself (a library without
      // sources).
      projectServices.register(classOf[com.intellij.psi.impl.JavaPsiImplementationHelper])(new Host_JavaPsiImplementationHelper(dispatch {
        case ("JavaPsiImplementationHelper.getClsFileNavigationElement", Array(f: com.intellij.psi.PsiJavaFile)) =>
          val source = for {
            classFile <- Option(f.getVirtualFile).collect { case lib: LibraryFile => lib }
            sourceRoot <- Option(librarySources.get(classFile.root))
            sourceName <- f.getClasses.headOption.collect { case c: com.intellij.psi.impl.compiled.ClsClassImpl => c.getSourceFileName }
            directory = classFile.entry.lastIndexOf('/') match { case -1 => "" case i => classFile.entry.substring(0, i + 1) }
            sourceFile <- Option(sourceRoot.findFileByRelativePath(directory + sourceName))
            psi <- Option(psiManagerRef.findFile(sourceFile))
          } yield psi
          source.getOrElse(f)
        // A class of a file that is not a copy is its own original.
        case ("JavaPsiImplementationHelper.getOriginalClass", Array(c: PsiClass)) if c.getContainingFile.getOriginalFile eq c.getContainingFile => c
        // Java sources parse at the highest language level: it accepts the syntax of every Java release the build may
        // target (the build's own release is not read yet).
        case ("JavaPsiImplementationHelper.getEffectiveLanguageLevel", _) => com.intellij.pom.java.LanguageLevel.HIGHEST
        case ("JavaPsiImplementationHelper.getDefaultImportAnchor",
              Array(list: com.intellij.psi.PsiImportList, statement: com.intellij.psi.PsiImportStatementBase)) =>
          new com.intellij.psi.impl.source.codeStyle.ImportHelper(
            com.intellij.psi.codeStyle.JavaCodeStyleSettings.getInstance(statement.getContainingFile)).getDefaultAnchor(list, statement)
      }))
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.importFilter", classOf[com.intellij.codeInsight.ImportFilter])
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.codeStyle.ReferenceAdjuster", classOf[LanguageExtensionPoint[?]])
      com.intellij.psi.codeStyle.ReferenceAdjuster.Extension.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE,
        new com.intellij.psi.impl.source.codeStyle.JavaReferenceAdjuster)
      // Java completion renders static-import preferences and inserts source imports through the platform manager.
      projectServices.register(classOf[com.intellij.psi.codeStyle.JavaCodeStyleManager])(
        new com.intellij.psi.impl.source.codeStyle.JavaCodeStyleManagerImpl(project))
      // Source classes combine Scala synthetic representations with Java stub entries; Java binaries remain on demand.
      lazy val scalaShortNames = new org.jetbrains.plugins.scala.caches.ScalaShortNamesCache(using project)
      projectServices.register(classOf[com.intellij.psi.search.PsiShortNamesCache])(new Host_PsiShortNamesCache(dispatch {
        case ("PsiShortNamesCache.getClassesByName", Array(name: String, scope: com.intellij.psi.search.GlobalSearchScope)) =>
          val binaries = libraryFiles.filesNamed(name + ".class").collect {
            case binary: LibraryFile if !binary.isLibrarySource && !binary.isDirectory && scope.contains(binary) => binary
          }.flatMap(binary => Option(psiManagerRef.findFile(binary))).collect {
            case owner: com.intellij.psi.PsiClassOwner => owner.getClasses.toSeq
          }.flatten.filter(_.getName == name)
          (scalaShortNames.getClassesByName(name, scope).toSeq ++
            StubIndex.getElements(JavaStubIndexKeys.CLASS_SHORT_NAMES,
              name, project, scope, classOf[PsiClass]).asScala ++ binaries).distinct.toArray
        // Inherited main/suite discovery passes the finite set of superclass files, including Java binaries.
        case ("PsiShortNamesCache.getMethodsByName", Array(name: String, scope: GlobalSearchScope)) =>
          val files = if (scope == GlobalSearchScope.EMPTY_SCOPE) Seq.empty
          else {
            val enumeration = com.intellij.psi.search.impl.VirtualFileEnumeration.extract(scope)
            Option(enumeration).flatMap(value => Option(value.getFilesIfCollection)).map(_.asScala.toSeq)
              .getOrElse(CallLog.unsupported("PsiShortNamesCache.getMethodsByName requires a finite file scope"))
          }
          def methods(owner: PsiClass): Seq[com.intellij.psi.PsiMethod] =
            owner.getMethods.toSeq.filter(_.getName == name) ++ owner.getInnerClasses.toSeq.flatMap(methods)
          files.flatMap { source =>
            com.intellij.openapi.progress.ProgressManager.checkCanceled()
            Option(psiManagerRef.findFile(source)).toSeq.collect {
              case file: com.intellij.psi.PsiJavaFile => file.getClasses.toSeq.flatMap(methods)
            }.flatten
          }.distinct.toArray
        case ("PsiShortNamesCache.getAllClassNames", _) =>
          (scalaShortNames.getAllClassNames.toSeq ++ StubIndex.getInstance
            .getAllKeys(JavaStubIndexKeys.CLASS_SHORT_NAMES, project).asScala ++ libraryFiles.classFileNames).distinct.sorted.toArray
      }))
      // Annotation lookups cache by owner; the host has no mirrored elements (light copies of sources), so an owner is
      // its own cache key.
      projectServices.register(classOf[com.intellij.codeInsight.AnnotationCacheOwnerNormalizer])(new Host_AnnotationCacheOwnerNormalizer(dispatch {
        case ("AnnotationCacheOwnerNormalizer.doNormalize", Array(owner)) => owner
      }))
      // Auto-import exclusions, read by quick fixes that look for importable implicit instances: the defaults.
      projectServices.register(classOf[com.intellij.codeInsight.JavaProjectCodeInsightSettings])(new com.intellij.codeInsight.JavaProjectCodeInsightSettings)
      // Code-insight contexts (multiverse: one file seen from several modules): off, so every file is in the
      // default context, as in the IDE without multiverse.
      projectServices.register(classOf[com.intellij.codeInsight.multiverse.CodeInsightContextManager])(
        tableProxy(classOf[com.intellij.codeInsight.multiverse.CodeInsightContextManager], "CodeInsightContextManager") {
          case ("getCodeInsightContext" | "getCodeInsightContextRaw" | "getPreferredContext", _) =>
            com.intellij.codeInsight.multiverse.CodeInsightContexts.defaultContext()
          case ("getCodeInsightContexts", _) => java.util.List.of(com.intellij.codeInsight.multiverse.CodeInsightContexts.defaultContext())
        })
      // Java code style of decompiled class files (indents): the platform's core defaults, as headless compilers use.
      appServices.register(classOf[com.intellij.psi.codeStyle.JavaFileCodeStyleFacadeFactory])(
        tableProxy(classOf[com.intellij.psi.codeStyle.JavaFileCodeStyleFacadeFactory], "JavaFileCodeStyleFacadeFactory") {
          case ("createFacade", _) => new com.intellij.core.CoreJavaFileCodeStyleFacade
        })
      // The IDE decompiles a Java class file (its text, for the class's PSI mirror) in its default project, as a
      // throwaway class-file PSI; the host's one project takes that role.
      appServices.register(classOf[com.intellij.openapi.project.DefaultProjectFactory])(new Host_DefaultProjectFactory(dispatch {
        case ("DefaultProjectFactory.getDefaultProject", _) => project
      }))
      projectServices.register(classOf[com.intellij.psi.codeStyle.CodeStyleManager])(
        new com.intellij.psi.impl.source.codeStyle.CodeStyleManagerImpl(project))
      // No language injection in the host: every file is its own top-level file.
      projectServices.register(classOf[com.intellij.lang.injection.InjectedLanguageManager])(new Host_InjectedLanguageManager(dispatch {
        case ("InjectedLanguageManager.getTopLevelFile", Array(e: com.intellij.psi.PsiElement)) => e.getContainingFile
        case ("InjectedLanguageManager.findInjectedElementAt" | "InjectedLanguageManager.getInjectedPsiFiles", _) => null
        case ("InjectedLanguageManager.isInjectedViewProvider", _) => java.lang.Boolean.FALSE
        case ("InjectedLanguageManager.getCachedInjectedDocumentsInRange", _) => java.util.List.of()
        // There are no registered injectors, so enumeration has no injected fragments to visit.
        case ("InjectedLanguageManager.enumerate", _) => null
      }))
      // JVM-language-neutral class lookup that JavaPsiFacadeImpl also consults; no other JVM language provides classes.
      CoreApplicationEnvironment.registerExtensionPoint(area, com.intellij.lang.jvm.facade.JvmElementProvider.EP_NAME.getName,
        classOf[com.intellij.lang.jvm.facade.JvmElementProvider])
      projectServices.register(classOf[com.intellij.lang.jvm.facade.JvmFacade])(new com.intellij.lang.jvm.facade.JvmFacadeImpl(project))
      projectServices.register(classOf[com.intellij.psi.impl.file.impl.JavaFileManager])(
        tableProxy(classOf[com.intellij.psi.impl.file.impl.JavaFileManager], "JavaFileManager") {
          // The build's source roots have no package prefix.
          case ("getNonTrivialPackagePrefixes", _) => java.util.List.of[String]()
        })
      projectServices.register(classOf[com.intellij.psi.JavaPsiFacade])(new com.intellij.psi.impl.JavaPsiFacadeImpl(project, null))
      // Resolution inside Java sources (mixed projects).
      projectServices.register(classOf[com.intellij.psi.PsiResolveHelper])(new com.intellij.psi.impl.source.resolve.PsiResolveHelperImpl(project))
      // ScalaDocParserDefinition reads TAB_SIZE from code style: one fixed settings object, never the scheme manager.
      projectServices.register(classOf[com.intellij.psi.codeStyle.ProjectCodeStyleSettingsManager]) {
        val settings = new com.intellij.psi.codeStyle.ProjectCodeStyleSettingsManager(project, /* loadExtensions = */ false)
        settings.USE_PER_PROJECT_SETTINGS = true
        settings.setMainProjectCodeStyle(new com.intellij.psi.codeStyle.CodeStyleSettings)
        settings
      }
      projectServices.register(classOf[com.intellij.psi.impl.ResolveScopeManager])(new Host_ResolveScopeManager(dispatch {
        // Sources resolve in their module, library classes in the modules that have the library, anything else everywhere;
        // sources are used in their module and the modules that depend on it.
        // Stub types and other synthetic files carry their declaration's context, as ResolveScopeManagerImpl follows.
        case ("ResolveScopeManager.getResolveScope", Array(e: com.intellij.psi.PsiElement)) =>
          contextFile(e).flatMap(moduleModel.resolveScope).getOrElse(everythingScope)
        case ("ResolveScopeManager.getUseScope", Array(e: com.intellij.psi.PsiElement)) =>
          contextFile(e).flatMap(moduleModel.useScope).getOrElse(everythingScope)
        case ("ResolveScopeManager.getDefaultResolveScope", Array(f: VirtualFile)) =>
          moduleModel.resolveScope(f).getOrElse(everythingScope)
      }))
      // Compiler indices (searching bytecode for implicit, apply and SAM usages) need a project task runner, JPS or sbt
      // shell, which the host lacks: the settings find none, are inactive, and the plugin searches the text only.
      declare("com.intellij.projectTaskRunner")
      projectServices.register(classOf[com.intellij.task.ProjectTaskManager])(new Host_ProjectTaskManager(project, dispatch(PartialFunction.empty)))
      projectServices.register(classOf[org.jetbrains.plugins.scala.settings.CompilerIndicesSettings])(
        new org.jetbrains.plugins.scala.settings.CompilerIndicesSettings(project))
      locally {
        val cachesClass = Class.forName("com.intellij.psi.impl.search.HighlightingCaches").asInstanceOf[Class[AnyRef]]
        projectServices.register(cachesClass)(newPrivate(cachesClass, project))
      }
      projectServices.register(classOf[com.intellij.psi.search.PsiSearchHelper])(new com.intellij.psi.impl.search.PsiSearchHelperImpl(project))
      // The options of the plugin's find-usages handler: its own defaults.
      projectServices.register(classOf[org.jetbrains.plugins.scala.findUsages.factory.ScalaFindUsagesConfiguration])(
        new org.jetbrains.plugins.scala.findUsages.factory.ScalaFindUsagesConfiguration(project))
      // Index-backed Scala symbol lookup: the real class, answered by the host's StubIndex.
      projectServices.register(classOf[org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager])(
        new org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager(using project))
      // LibraryExtensionsManager is built by its own constructor. With library extensions disabled it loads nothing,
      // so dynamic extension lookups (SyntheticMembersInjector, ScalaMacroEvaluator) answer "none". It keeps a
      // PropertiesComponent that it reads only when extensions are enabled.
      projectServices.register(classOf[com.intellij.ide.util.PropertiesComponent])(new Host_PropertiesComponent(dispatch(PartialFunction.empty)))
      projectServices.register(classOf[org.jetbrains.plugins.scala.components.libextensions.LibraryExtensionsManager])(
        new org.jetbrains.plugins.scala.components.libextensions.LibraryExtensionsManager(project))
      // Settings are plain state beans with defaults (later: from LSP configuration).
      projectServices.register(classOf[org.jetbrains.plugins.scala.settings.ScalaProjectSettings]) {
        val settings = new org.jetbrains.plugins.scala.settings.ScalaProjectSettings(project)
        // Library extensions are jars downloaded from build resolvers and loaded into the IDE: out of scope.
        settings.setEnableLibraryExtensions(false)
        // Where errors come from: the build's compiler (the caller adds its problems) or the plugin's type-aware checks.
        settings.setCompilerHighlightingScala3(config.compilerErrorsScala3)
        settings.setCompilerHighlightingScala2(config.compilerErrorsScala2)
        // Type mismatches as diagnostics on the expression, not as editor inlay hints (which LSP clients lack).
        settings.setTypeMismatchHints(false)
        settings
      }
      projectServices.register(classOf[org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager])(
        new org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager(using project))
      projectServices.register(classOf[org.jetbrains.plugins.scala.lang.psi.types.api.StdTypes])(
        new org.jetbrains.plugins.scala.lang.psi.types.api.StdTypes(using new ProjectContext(project)))
      projectServices.register(classOf[org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.SyntheticClasses])(
        new org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.SyntheticClasses(project))
      // Scala 3 type operations (union joins).
      projectServices.register(classOf[org.jetbrains.plugins.scala.lang.psi.types.Scala3Bounds])(
        org.jetbrains.plugins.scala.lang.psi.types.Scala3Bounds(project))
      projectServices.register(classOf[org.jetbrains.plugins.scala.lang.macros.evaluator.ScalaMacroEvaluator])(
        new org.jetbrains.plugins.scala.lang.macros.evaluator.ScalaMacroEvaluator(project))
      // Private light services (@Service): the container builds them by class name, and so does the host.
      Seq(
        "org.jetbrains.plugins.scala.lang.psi.types.api.ParameterizedType$SubstitutorCacheService",
        "org.jetbrains.plugins.scala.util.UnloadAwareDisposable$UnloadAwareDisposableService",
        "org.jetbrains.plugins.scala.lang.psi.types.ScMatchType$MatchTypeReductionCacheService",
      ).foreach { name =>
        val cls = Class.forName(name).asInstanceOf[Class[AnyRef]]
        projectServices.register(cls)(newPrivate(cls))
      }

      // Element finders are built last: their constructors fetch project services.
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.java.elementFinder", classOf[com.intellij.psi.PsiElementFinder])
      val finders = area.getExtensionPoint[com.intellij.psi.PsiElementFinder]("com.intellij.java.elementFinder")
        .asInstanceOf[ExtensionPointImpl[com.intellij.psi.PsiElementFinder]]
      finders.registerExtension(new LibraryClassFinder(project, libraryFiles, moduleModel), root)
      finders.registerExtension(new org.jetbrains.plugins.scala.finder.ScalaClassFinder(project), root)
      finders.registerExtension(new org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.SyntheticClassElementFinder(project), root)
    }

    /** Invalidates source/library PSI caches and the platform's project modification counter. */
    private def psiChanged(): Unit = HostLock.write {
      ScalaPsiHost.notifyPsiChanged(project)
      psiModTracker.incCounter()
      org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiManager.instance(project).clearOnNonScalaChange()
    }

    /** Runs the plugin's project startup activity after all services and element finders are registered. */
    private def startProject(): ScalaPsiHost = {
      // ---- 4. Project startup the PSI depends on -------------------------------------------------
      // The plugin's own activity registers the synthetic classes (Any, Int, ...) through DumbService.queueTask.
      new org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.RegisterSyntheticClassesProjectActivity().execute(project)

      new ScalaPsiHost(config.stateDir, project, symbolIndex, wordIndex, importIndex, libraryFiles, librarySources, moduleModel, () => psiChanged(),
        new CodeActions(Descriptors.scalaExtensions(config.scalaPluginJars, "com.intellij.intentionAction"),
          Descriptors.scalaExtensions(config.scalaPluginJars, "com.intellij.lang.surroundDescriptor")),
        new Inspections(Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.localInspection")))
    }
  }

  /** Invokes a plugin/platform constructor that the IDE normally instantiates through its service container. */
  private def newPrivate[T](cls: Class[T], args: AnyRef*): T = {
    val constructor = cls.getDeclaredConstructors.find(_.getParameterCount == args.size)
      .getOrElse(throw new IllegalStateException(s"no ${args.size}-argument constructor in ${cls.getName}"))
    constructor.setAccessible(true)
    cls.cast(constructor.newInstance(args*))
  }
}
