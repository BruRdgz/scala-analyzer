package scalaanalyzer.platform

import com.intellij.codeInsight.javadoc.{DocumentationDelegateProvider, JavaDocInfoGeneratorFactory}
import com.intellij.codeInsight.hints.settings.ParameterNameHintsSettings
import com.intellij.codeInsight.hints.{InlayParameterHintsExtension, InlayParameterHintsProvider}
import com.intellij.codeInsight.editorActions.ExtendWordSelectionHandler
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.core.{CoreApplicationEnvironment, CoreFileTypeRegistry}
import com.intellij.ide.highlighter.HtmlFileHighlighter
import com.intellij.lang.documentation.{ClientDocumentationSettings, DocumentationSettings}
import com.intellij.lang.html.HTMLLanguage
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.{PsiErrorElement, PsiFile, PsiParserFacade}
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
import com.intellij.openapi.util.{Condition, Disposer, Key, TextRange, UserDataHolderBase}
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.{ReadonlyStatusHandler, VirtualFile}
import com.intellij.featureStatistics.FeatureUsageTracker
import com.intellij.lang.parameterInfo.{LanguageParameterInfo, ParameterInfoHandler}
import com.intellij.navigation.ItemPresentationProviders
import com.intellij.psi.{LanguageFileViewProviders, PsiClass, PsiElement, PsiField, PsiManager}
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
import com.intellij.codeInsight.{
  AnnotationCacheOwnerNormalizer, AutoPopupController, AutoPopupControllerHelper, CodeInsightSettings,
  ContextNullabilityInfo, CustomExceptionHandler, ExternalAnnotationsManager, FileModificationService, ImportFilter,
  InferredAnnotationsManager, JavaIdeCodeInsightSettings, JavaProjectCodeInsightSettings, NullableNotNullManager,
  TailTypeFactory, TargetElementUtil, TargetElementUtilBase, TestFrameworks, TestFrameworksImpl
}
import com.intellij.codeInsight.completion.{
  CompletionService, CompletionType, FrontendFriendlyTailTypeFactory, InternalCompletionSettings,
  NewRdCompletionSupport
}
import com.intellij.codeInsight.completion.group.GroupedCompletion
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter
import com.intellij.codeInsight.daemon.impl.analysis.HighlightingLevelManager
import com.intellij.codeInsight.editorActions.TabOutScopesTracker
import com.intellij.codeInsight.guess.GuessManager
import com.intellij.codeInsight.guess.impl.GuessManagerImpl
import com.intellij.codeInsight.highlighting.HighlightErrorFilter
import com.intellij.codeInsight.multiverse.{CodeInsightContextManager, CodeInsightContexts, MultiverseEnabler}
import com.intellij.codeInsight.navigation.{ClassImplementationsSearch, MethodImplementationsSearch}
import com.intellij.codeInsight.runner.JavaMainMethodProvider
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInspection.{GlobalInspectionContext, InspectionManager, InspectionManagerBase, SuppressManager}
import com.intellij.codeInspection.ex.{EntryPointsManager, EntryPointsManagerBase}
import com.intellij.concurrency.JobLauncher
import com.intellij.core.{CoreJavaFileCodeStyleFacade, CoreJobLauncher, CorePsiPackageImplementationHelper}
import com.intellij.diagnostic.{PluginException, PluginProblemReporter}
import com.intellij.find.FindUsagesSettings
import com.intellij.find.impl.FindUsagesSettingsImpl
import com.intellij.formatting.{Formatter, FormatterImpl, FormattingProgressCallbackFactory}
import com.intellij.formatting.service.{CoreFormattingService, FormattingService}
import com.intellij.ide.IconProvider
import com.intellij.ide.highlighter.{JavaClassFileType, JavaFileType}
import com.intellij.ide.util.PropertiesComponent
import com.intellij.internal.statistic.eventLog.{StatisticsEventLogProvidersHolder, StatisticsEventLoggerProvider}
import com.intellij.java.codeserver.highlighting.JavaErrorFilter
import com.intellij.java.frontback.psi.impl.syntax.JavaSyntaxDefinitionExtension
import com.intellij.java.impl.template.JavaTemplateCodeInsightSupport
import com.intellij.lang.{
  LanguageFormatting, LanguageFormattingRestriction, LanguageImportStatements, LanguageNamesValidation, MetaLanguage
}
import com.intellij.lang.cacheBuilder.{CacheBuilderRegistry, WordsScanner}
import com.intellij.lang.customFolding.{NetBeansCustomFoldingProvider, VisualStudioCustomFoldingProvider}
import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.lang.folding.CustomFoldingProvider
import com.intellij.lang.impl.modcommand.ModCommandServiceImpl
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.java.{
  JavaFindUsagesProvider, JavaFormattingModelBuilder, JavaParserDefinition, JavaSyntaxHighlighterFactory
}
import com.intellij.lang.java.syntax.JavaElementTypeConverterExtension
import com.intellij.lang.jvm.facade.{JvmElementProvider, JvmFacade, JvmFacadeImpl}
import com.intellij.mock.MockFileDocumentManagerImpl
import com.intellij.modcommand.{ModCommandExecutor, ModCommandService}
import com.intellij.modcompletion.ModCompletionItemProvider
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.{
  AsyncExecutionService, ReadActionListener, TransactionGuard, TransactionGuardImpl, WriteActionListener,
  WriteIntentReadActionListener
}
import com.intellij.openapi.application.ex.ApplicationEx
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.actions.FlipCommaIntention
import com.intellij.openapi.editor.actions.lists.{JoinLinesIntention, ListSplitJoinContext, SplitLineIntention}
import com.intellij.openapi.editor.colors.{EditorColorsManager, EditorColorsScheme, TextAttributesKey}
import com.intellij.openapi.editor.elf.Elf
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.extensions.{ExtensionDescriptor, LoadingOrder, PluginDescriptor}
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.{BinaryFileTypeDecompilers, FileType}
import com.intellij.openapi.module.{JdkApiCompatibilityService, ModuleManager, ModuleTypeManager}
import com.intellij.openapi.project.{DefaultProjectFactory, DumbUtil, IncompleteDependenciesService}
import com.intellij.openapi.projectRoots.{JavaVersionService, JavaVersionServiceImpl}
import com.intellij.openapi.roots.{
  FileIndexFacade, LanguageLevelProjectExtension, ModuleRootManager, PackageIndex, ProjectFileIndex,
  ProjectRootManager, ProjectRootModificationTracker, TestSourcesFilter
}
import com.intellij.openapi.roots.impl.PushedFilePropertiesRetriever
import com.intellij.openapi.roots.libraries.{LibraryTable, LibraryTablesRegistrar}
import com.intellij.openapi.util.{Computable, ModificationTracker, ThrowableComputable}
import com.intellij.openapi.util.io.BufferExposingByteArrayOutputStream
import com.intellij.openapi.util.registry.RegistryKeyDescriptor
import com.intellij.openapi.vfs.{VirtualFileSet, VirtualFileSetFactory}
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.openapi.vfs.newvfs.{AttributeOutputStream, ManagingFS}
import com.intellij.openapi.vfs.transformer.TextPresentationTransformers
import com.intellij.patterns.PlatformPatterns
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.syntax.psi.{
  CommonElementTypeConverterFactory, ElementTypeConverters, LanguageSyntaxDefinitions, PsiSyntaxBuilderFactory,
  PsiSyntaxBuilderFactoryImpl
}
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.pom.PomModel
import com.intellij.pom.java.{InternalPersistentJavaLanguageLevelReaderService, LanguageLevel}
import com.intellij.pom.tree.TreeAspect
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.{
  ClassFileViewProviderFactory, DumbJavaModuleGraphHelper, FileTypeFileViewProviders, JavaModuleGraphHelper,
  JavaPsiFacade, JvmPsiConversionHelper, LanguageSubstitutors, PackageDirectoryProvider, ParsingDiagnostics,
  PsiAnnotation, PsiClassOwner, PsiDocumentManager, PsiElementFactory, PsiElementFinder, PsiFileFactory,
  PsiImportList, PsiImportStatementBase, PsiJavaFile, PsiMethod, PsiNameHelper, PsiReference, PsiReferenceProvider,
  PsiReferenceRegistrar, PsiReferenceService, PsiReferenceServiceImpl, PsiResolveHelper, PsiSubstitutorFactory,
  SmartPointerManager
}
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.codeStyle.{
  CodeStyleManager, CodeStyleSettings, CodeStyleSettingsService, CommonCodeStyleSettings,
  CustomCodeStyleSettingsFactory, DocCommentSettings, JavaCodeStyleManager, JavaCodeStyleSettings,
  JavaFileCodeStyleFacadeFactory, LanguageCodeStyleProvider, ProjectCodeStyleSettingsManager, ReferenceAdjuster
}
import com.intellij.psi.compiled.ClassFileDecompilers
import com.intellij.psi.controlFlow.ControlFlowFactory
import com.intellij.psi.impl.{
  DocumentCommitProcessor, JavaClassSupersImpl, JavaPsiFacadeImpl, JavaPsiImplementationHelper,
  JvmPsiConversionHelperImpl, PsiCachedValuesFactory, PsiElementFactoryImpl, PsiFileFactoryImpl,
  PsiJavaModuleModificationTracker, PsiModificationTrackerImpl, PsiNameHelperImpl, PsiParserFacadeImpl,
  PsiSubstitutorFactoryImpl,
  ResolveScopeManager
}
import com.intellij.psi.impl.cache.impl.id.IdIndexers
import com.intellij.psi.impl.cache.impl.idCache.JavaIdIndexer
import com.intellij.psi.impl.compiled.{
  ClassFileDecompiler, ClassFileStubBuilder, ClsClassImpl, ClsCustomNavigationPolicy, ClsDecompilerImpl
}
import com.intellij.psi.impl.file.PsiPackageImplementationHelper
import com.intellij.psi.impl.file.impl.JavaFileManager
import com.intellij.psi.impl.java.stubs.{
  JavaStubDefinition, JavaStubElementTypePsiElementMappingRegistry, JavaStubRegistryExtension
}
import com.intellij.psi.impl.migration.PsiMigrationManager
import com.intellij.psi.impl.search.{
  CachesBasedRefSearcher, ImplicitClassesSearcher, JavaClassInheritorsSearcher, JavaOverridingMethodsSearcher,
  MethodDeepestSuperSearcher, MethodSuperSearcher, MethodUsagesSearcher, PsiSearchHelperImpl
}
import com.intellij.psi.impl.smartPointers.{SmartPointerAnchorProvider, SmartPointerManagerImpl}
import com.intellij.psi.impl.source.codeStyle.{
  CodeStyleManagerImpl, ImportHelper, IndentHelper, IndentHelperImpl, JavaCodeStyleManagerImpl,
  JavaReferenceAdjuster, PostFormatProcessor, PreFormatProcessor
}
import com.intellij.psi.impl.source.resolve.{JavaResolveCache, PsiResolveHelperImpl, ResolveCache}
import com.intellij.psi.impl.source.resolve.reference.{ReferenceProvidersRegistry, ReferenceProvidersRegistryImpl}
import com.intellij.psi.impl.source.tree.{JavaASTFactory, JavaTreeGenerator}
import com.intellij.psi.impl.source.tree.mvcc.{PsiVersioningGarbageCollector, PsiVersioningServiceImpl}
import com.intellij.psi.search.{
  EverythingGlobalScope, FilenameIndex, PredefinedSearchScopeProvider, ProjectScopeBuilder, PsiSearchHelper,
  PsiShortNamesCache
}
import com.intellij.psi.search.impl.VirtualFileEnumeration
import com.intellij.psi.search.searches.{ClassInheritorsSearch, DefinitionsScopedSearch, ReferencesSearch}
import com.intellij.psi.statistics.{StatisticsInfo, StatisticsManager}
import com.intellij.psi.stubs.{
  BinaryFileStubBuilders, CoreStubTreeLoader, StubElementRegistryServiceImpl, StubTreeLoader
}
import com.intellij.psi.util.{CachedValuesManager, JavaClassSupers, PsiVersioningService}
import com.intellij.task.ProjectTaskManager
import com.intellij.testIntegration.{JavaTestFinder, TestFinder, TestFramework}
import com.intellij.util.{
  CachedValuesManagerImpl, CollectionQuery, EmptyQuery, Queries, QueriesImpl, Query, QueryExecutor
}
import com.intellij.util.concurrency.TransferredWriteActionService
import com.intellij.util.gist.GistManager
import com.intellij.util.indexing.ID
import com.intellij.util.messages.MessageBusOwner
import com.intellij.util.xml.dom.XmlElement
import org.jetbrains.plugins.scala.codeInsight.ScalaCodeInsightSettings
import org.jetbrains.plugins.scala.externalLibraries.bm4.BetterMonadicForSupport
import org.jetbrains.plugins.scala.externalLibraries.kindProjector.KindProjectorUtil
import org.jetbrains.plugins.scala.highlighter.{ScalaCommenter, ScalaSyntaxHighlighterFactory}
import org.jetbrains.plugins.scala.project.ProjectContext
import org.jetbrains.plugins.scalaDoc.ScalaDocLanguage
import org.jetbrains.plugins.scalaDoc.highlighter.ScalaDocSyntaxHighlighter
import org.jetbrains.plugins.scalaDirective.ScalaDirectiveLanguage
import org.jetbrains.plugins.scalaDirective.highlighter.ScalaDirectiveSyntaxHighlighter
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.plugins.scala.{Scala3Language, ScalaFileType, ScalaLanguage}
import org.jetbrains.plugins.scala.autoImport.ImportOrderingIndexer
import org.jetbrains.plugins.scala.caches.{ScalaShortNamesCache, ScalaShortNamesCacheManager}
import org.jetbrains.plugins.scala.codeInsight.ScalaTargetElementEvaluator
import org.jetbrains.plugins.scala.codeInsight.highlighting.ScalaDocHighlightErrorFilter
import org.jetbrains.plugins.scala.components.libextensions.LibraryExtensionsManager
import org.jetbrains.plugins.scala.editor.importOptimizer.ScalaImportOptimizer
import org.jetbrains.plugins.scala.findUsages.factory.ScalaFindUsagesConfiguration
import org.jetbrains.plugins.scala.finder.ScalaClassFinder
import org.jetbrains.plugins.scala.lang.findUsages.ScalaFindUsagesProvider
import org.jetbrains.plugins.scala.lang.formatting.{ScalaFormattingModelBuilder, ScalaLanguageFormattingRestriction}
import org.jetbrains.plugins.scala.lang.formatting.processors.{
  ScalaBracePostFormatProcessor, ScalaDocNewlinedPreFormatProcessor, ScalaTrailingCommaProcessor
}
import org.jetbrains.plugins.scala.lang.formatting.settings.ScalaCodeStyleSettings
import org.jetbrains.plugins.scala.lang.macros.evaluator.ScalaMacroEvaluator
import org.jetbrains.plugins.scala.lang.parser.{
  Scala3ParserDefinition, ScalaASTFactory, ScalaLanguageSubstitutor, ScalaParserDefinition
}
import org.jetbrains.plugins.scala.lang.psi.ScFileViewProviderFactory
import org.jetbrains.plugins.scala.lang.psi.api.base.literals.ScStringLiteral
import org.jetbrains.plugins.scala.lang.psi.api.expr.ExpectedTypes
import org.jetbrains.plugins.scala.lang.psi.compiled.ScClassFileDecompiler
import org.jetbrains.plugins.scala.lang.psi.impl.{ScalaPsiChangeListener, ScalaPsiManager}
import org.jetbrains.plugins.scala.lang.psi.impl.expr.ExpectedTypesImpl
import org.jetbrains.plugins.scala.lang.psi.impl.toplevel.synthetic.{
  RegisterSyntheticClassesProjectActivity, SyntheticClassElementFinder, SyntheticClasses
}
import org.jetbrains.plugins.scala.lang.psi.stubs.{
  Scala3LanguageStubDefinition, ScalaLanguageStubDefinition, ScalaStubRegistryExtension
}
import org.jetbrains.plugins.scala.lang.psi.types.Scala3Bounds
import org.jetbrains.plugins.scala.lang.psi.types.api.StdTypes
import org.jetbrains.plugins.scala.lang.refactoring.ScalaNamesValidator
import org.jetbrains.plugins.scala.lang.scaladoc.reflinks.ScalaDocRefLinkParserDefinition
import org.jetbrains.plugins.scala.project.settings.ScalaCompilerConfiguration
import org.jetbrains.plugins.scala.scalaMeta.ScalaMetaApi
import org.jetbrains.plugins.scala.settings.{
  CompilerIndicesSettings, ScalaApplicationSettings, ScalaCodeFoldingSettings, ScalaCodeFoldingSettingsImpl,
  ScalaHighlightingMode, ScalaProjectSettings
}
import org.jetbrains.plugins.scala.tasty.{
  TastyDecompiler, TastyFileStubBuilder, TastyFileType, TastyFileViewProviderFactory
}
import org.jetbrains.plugins.scalaDirective.lang.parser.ScalaDirectiveParserDefinition
import org.jetbrains.plugins.scalaDoc.lang.parser.ScalaDocParserDefinition
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScopeKt
import scalaanalyzer.{HostConfig, ModuleModel, ScalaPsiHost}
import scalaanalyzer.language.{
  ChangeEngine, CodeActions, Completion, InlayHints, Inspections, PostfixCompletion, Rename, TestLenses
}
import scalaanalyzer.platform.{Descriptors, ExtensionBean, dispatch, tableProxy}
import scalaanalyzer.ScalaPsiHost.{CompletionWeighers, SemanticExtensionPoints, scalaCompletionContributors}

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.{Set as JavaSet}
import java.util.concurrent.{ConcurrentHashMap, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.jdk.CollectionConverters.*
import scala.meta.intellij.ScalaMetaApiImpl

/**
 * Installs the platform state that hosts the Scala plugin's PSI and builds the [[ScalaPsiHost]] on top of it.
 *
 * The platform keeps the Application and the root extension area in process-wide statics, so installation runs once
 * per JVM; [[ScalaPsiHost.start]] enforces that. Installation runs in four ordered phases: the application object
 * and its extension points, the language registries and index services, the project object and its services, and
 * the plugin's project startup. Services are registered in `ServiceTable`s and created on first request; a platform
 * call that no table answers fails loudly and is recorded in [[CallLog]].
 */
private[scalaanalyzer] object HostBootstrap {
  /** One platform completion weigher: its class name under `com.intellij.codeInsight.completion`, id and `order`. */
  private final case class WeigherDescriptor(className: String, id: String, order: String)

  /** Preserves descriptor ordering anchors while leaving completion statistics inert. */
  private val PlatformCompletionWeighers: Seq[ExtensionBean] = Seq(
    WeigherDescriptor("StatisticsWeigher", "stats", "after prefix"),
    WeigherDescriptor("PriorityWeigher", "priority", "first"),
    WeigherDescriptor("PrefixMatchingWeigher", "prefix", "after priority"),
    WeigherDescriptor("GroupingWeigher", "grouping", "last"),
    WeigherDescriptor("ExplicitProximityWeigher", "explicitProximity", "after stats"),
    WeigherDescriptor("LookupElementProximityWeigher", "proximity", "after explicitProximity"),
  ).map { weigher =>
    ExtensionBean(Map("key" -> "completion", "id" -> weigher.id, "order" -> weigher.order,
      "implementationClass" -> s"com.intellij.codeInsight.completion.${weigher.className}"))
  }

  /** The host's own Scala completion contributors; `first`, so a plugin contributor's `stopHere` cannot hide them. */
  private val HostCompletionContributors = Seq(ExtensionBean(Map("language" -> "Scala", "id" -> "hostPostfixTemplates",
    "order" -> "first", "implementationClass" -> classOf[PostfixCompletion.Contributor].getName)))

  /** The platform's Java completion contributor as a descriptor bean, registered under the Java plugin's id. */
  private val JavaCompletionContributors = Seq(ExtensionBean(Map("language" -> "JAVA",
    "implementationClass" -> "com.intellij.codeInsight.completion.JavaCompletionContributor", "id" -> "java")))

  /**
   * The platform's split/join and flip-comma intentions: the Scala descriptors do not register them, and they act
   * through the list contexts the host registers from the plugin's descriptors.
   */
  private val PlatformListIntentions: Seq[String] =
    Seq(classOf[SplitLineIntention], classOf[JoinLinesIntention], classOf[FlipCommaIntention]).map(_.getName)

  /**
   * Installs the process-wide platform and returns the ready PSI host.
   *
   * Points the platform's `idea.{home,system,config,log}.path` properties at subdirectories of `config.stateDir`
   * (created if absent) before any platform class reads them, installs the platform logger factory, and runs the
   * bootstrap phases. The caller ensures this runs at most once per JVM. If installation fails, the root disposable
   * is disposed and the failure is rethrown with any disposal failure attached as suppressed; on success a JVM
   * shutdown hook disposes it.
   *
   * @param config host settings; `stateDir` receives the platform's state directories and `scalaPluginJars` locate
   *               the plugin's descriptors
   * @return the host with all services installed and the synthetic classes registered
   */
  def start(config: HostConfig): ScalaPsiHost = {
    // PathManager reads these on first use; the ID enumeration of index keys is persisted under the system path.
    for (kind <- Seq("home", "system", "config", "log")) {
      val dir = config.stateDir.resolve(kind)
      Files.createDirectories(dir)
      System.setProperty(s"idea.$kind.path", dir.toString)
    }

    Logger.setFactory((category: String) => new HostLogger(category))

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

  /**
   * The ordered installation state shared by the four bootstrap phases.
   *
   * Single use: [[HostBootstrap.start]] constructs one and calls `start()`, which runs the phases in declaration
   * order. Members that platform classes reach while services are being created are lazy, because the proxies and
   * their answer tables refer to each other (`app` and `area`, `project` and `moduleModel`).
   *
   * @param config host settings
   * @param root disposable that owns everything the installation registers
   */
  private final class Bootstrap(private val config: HostConfig, private val root: Disposable) {
    /** Application-level services, answered by the application proxy's `getService`. */
    private val appServices = new ServiceTable("Application")

    /** Implementation class names of the Scala, Java and host completion contributors, made without injection. */
    private val completionContributors =
      (scalaCompletionContributors(config.scalaPluginJars) ++ HostCompletionContributors ++ JavaCompletionContributors)
        .map(_.implementation).toSet

    /** Project-level services, answered by the project proxy's `getService`. */
    private val projectServices = new ServiceTable("Project")

    /** User data behind the application proxy's `getUserData`. */
    private val appData = new UserDataHolderBase

    /** User data behind the project proxy's `getUserData` and `putUserData`. */
    private val projectData = new UserDataHolderBase

    /** An inert in-process bus: platform classes subscribe in constructors; the host never publishes IDE events. */
    private val busOwner = tableProxy(classOf[MessageBusOwner], "MessageBusOwner") {
      case ("isDisposed", _) => java.lang.Boolean.FALSE
    }

    /** The application message bus, owned by `busOwner`. */
    private lazy val appBus = MessageBusFactoryImpl.createRootBus(busOwner)

    /** The root extension area, returned by the application and project proxies. */
    private lazy val area: ExtensionsAreaImpl = new ExtensionsAreaImpl(app)

    /** Uses the preview thread's copy-local write path, otherwise the host's project write lock. */
    private def runWriteAction[T](body: => T): T =
      if (IntentionPreviewUtils.isIntentionPreviewActive) body else HostLock.write(body)

    /** The application object: a proxy for ApplicationEx, the interface the IDE's application implements. */
    private lazy val app: Application = tableProxy(classOf[ApplicationEx], "Application") {
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
      case ("runWriteIntentReadAction", Array(c: ThrowableComputable[?, ?])) =>
        // A Java generic result: typed `?` statically, always a reference at runtime.
        HostLock.writeIntent(c.compute()).asInstanceOf[AnyRef]
      case ("addReadActionListener", Array(listener: ReadActionListener, parent: Disposable)) =>
        HostLock.addReadListener(listener, parent)
        null
      case ("addWriteIntentReadActionListener", Array(listener: WriteIntentReadActionListener, parent: Disposable)) =>
        HostLock.addIntentListener(listener, parent)
        null
      case ("addWriteActionListener", Array(listener: WriteActionListener, parent: Disposable)) =>
        HostLock.addWriteListener(listener, parent)
        null
      // Reads and writes are the host's lock (HostLock): access is allowed where the thread holds it.
      case ("isReadAccessAllowed", _) => java.lang.Boolean.valueOf(HostLock.isReading || HostLock.hasWriteIntent)
      case ("assertReadAccessAllowed", _) =>
        if (!HostLock.isReading && !HostLock.hasWriteIntent)
          throw new IllegalStateException("read access is allowed inside read or write actions only")
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
      // Java generic results (the next four cases): typed `?` statically, always references at runtime.
      case ("runWriteAction", Array(c: Computable[?])) => runWriteAction(c.compute()).asInstanceOf[AnyRef]
      case ("runWriteAction", Array(c: ThrowableComputable[?, ?])) => runWriteAction(c.compute()).asInstanceOf[AnyRef]
      case ("assertIsNonDispatchThread" | "assertReadAccessNotAllowed", _) => null
      case ("isUnitTestMode", _) =>
        java.lang.Boolean.valueOf(
          config.unitTestMode || Rename.isAnsweringDialogs || PostfixCompletion.isAnsweringDialogs)
      case ("isInternal" | "isEAP" | "isDisposed", _) => java.lang.Boolean.FALSE
      case ("isHeadlessEnvironment" | "isCommandLine", _) => java.lang.Boolean.TRUE
      // The stored value is a reference of unknown type `?`.
      case ("getUserData", Array(k: Key[?])) => appData.getUserData(k).asInstanceOf[AnyRef]
      // Extension points resolve their declared class through the component manager.
      case ("loadClass", Array(name: String, _)) => Class.forName(name)
      case ("isInjectionForExtensionSupported", _) => java.lang.Boolean.FALSE
      // Completion bean extensions use the platform's lazy loader, without container injection.
      case ("instantiateClass", Array(cls: Class[?], _)) if
          completionContributors(cls.getName) ||
          ScalaPsiHost.CompletionWeighers.contains(cls.getName) ||
          Set("com.intellij.codeInsight.completion.CompletionContributorEP", "com.intellij.psi.WeigherExtensionPoint")
            .contains(cls.getName) =>
        // An instance of a loaded class: a reference, typed by the capture of `Class[?]`.
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
      // Java generic results (the next two cases): typed `?` statically, always references at runtime.
      case ("runReadAction", Array(c: Computable[?])) => HostLock.readLocked(c.compute()).asInstanceOf[AnyRef]
      case ("runReadAction", Array(c: ThrowableComputable[?, ?])) =>
        HostLock.readLocked(c.compute()).asInstanceOf[AnyRef]
    }

    /** Extension-point declarations from the platform and Scala plugin descriptors, keyed by extension-point name. */
    private lazy val epDecls: Map[String, EpDecl] = {
      val classpathJars = System.getProperty("java.class.path").split(File.pathSeparator).toSeq
        .filter(_.endsWith(".jar")).map(Path.of(_))
      Descriptors.extensionPoints(classpathJars, config.scalaPluginJars)
    }

    /** Registers a descriptor-declared extension point and returns its platform registry; fails if undeclared. */
    private def declare(name: String): ExtensionPointImpl[?] = {
      val declaration = epDecls.getOrElse(
        name, throw new IllegalStateException(s"extension point is not declared on the classpath: $name"))
      area.registerExtensionPoint(declaration.name, declaration.className, declaration.kind, declaration.dynamic)
      // `area` is an ExtensionsAreaImpl, which registers ExtensionPointImpl instances.
      area.getExtensionPoint(name).asInstanceOf[ExtensionPointImpl[?]]
    }

    /** The index behind the application's StubIndex service. */
    private lazy val symbolIndex = new SymbolIndex

    /** The library file system, closed when the root disposable is disposed. */
    private lazy val libraryFiles = {
      val files = new LibraryFileSystem
      Disposer.register(root, (() => files.close()): Disposable)
      files
    }

    /** Maps a library class root to its attached source root; read when Java class files navigate to sources. */
    private lazy val librarySources = new ConcurrentHashMap[VirtualFile, VirtualFile]()

    /** The project's PSI modification tracker, advanced by `psiChanged`. */
    private lazy val psiModTracker = new PsiModificationTrackerImpl(project)

    /** The project message bus, a child of the application bus. */
    private lazy val projectBus = new MessageBusFactoryImpl().createMessageBus(busOwner, appBus)

    /** Module metadata that the project, file-index, module and scope services answer from. */
    private lazy val moduleModel = new ModuleModel(project)

    /** The project object: a proxy for Project. */
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
      // The stored value is a reference of unknown type `?`.
      case ("getUserData", Array(k: Key[?])) => projectData.getUserData(k).asInstanceOf[AnyRef]
      case ("putUserData", Array(k: Key[?], v)) =>
        // The proxy passes the value untyped; the holder stores it without checking the key's type parameter.
        projectData.putUserData(k.asInstanceOf[Key[AnyRef]], v)
        null
    }

    /** The word index of project sources, answered for the platform's `IdIndex`. */
    private lazy val wordIndex = new WordIndex(project)

    /** The import-ordering index, answered for the Scala plugin's `ImportOrderingIndexer`. */
    private lazy val importIndex = new ImportIndex(project)

    /**
     * Runs the four phases in order.
     *
     * Call once: it sets the platform's process-wide application and root extension area.
     *
     * @return the host, once every service is installed and the synthetic classes are registered
     */
    def start(): ScalaPsiHost = {
      installApplication()
      installLanguageRegistries()
      installProjectServices()
      startProject()
    }

    /** Installs the application object, services, and descriptor-backed extension points. */
    private def installApplication(): Unit = {
      /** Registers completion descriptor beans while retaining their platform IDs and ordering metadata. */
      def registerCompletionBeans(epName: String, beans: Seq[ExtensionBean], plugin: PluginDescriptor): Unit = {
        // `area` is an ExtensionsAreaImpl, which registers ExtensionPointImpl instances.
        val ep = area.getExtensionPoint(epName).asInstanceOf[ExtensionPointImpl[?]]
        val descriptors = beans.map { bean =>
          val attributes = bean.attributes -- (if (epName.endsWith("weigher")) Seq("order") else Seq("id", "order"))
          new ExtensionDescriptor(null, null, bean.attributes.getOrElse("id", null),
            LoadingOrder.readOrder(bean.attributes.getOrElse("order", "")),
            new XmlElement(epName.stripPrefix("com.intellij."), attributes.asJava, java.util.List.of(), null), true)
        }
        ep.registerExtensions(descriptors.asJava, plugin, java.util.List.of())
      }

      // ---- 1. Application object -------------------------------------------------------------
      ApplicationManager.setApplication(app, root)
      Extensions.setRootArea(area)
      // The plugin's own extension points, with no extensions.
      val pluginEps = epDecls.values.filter(_.fromScalaPlugin)
      if (pluginEps.isEmpty)
        throw new IllegalStateException(
          s"no extension points declared by the Scala plugin jars ${config.scalaPluginJars.mkString(", ")}; " +
            "are they on the classpath?")
      pluginEps.foreach(d => declare(d.name))
      // The base handlers in intellij.platform.lang.impl.xml also run for Scala, before the plugin's handlers.
      declare(ExtendWordSelectionHandler.EP_NAME.getName)
      val selectioners = area.getExtensionPoint[ExtendWordSelectionHandler](ExtendWordSelectionHandler.EP_NAME.getName)
      val baseSelectioners = Seq(
        "PlainTextLineSelectioner", "NaturalLanguageTextSelectioner", "WordSelectioner", "LineCommentSelectioner",
        "BlockCommentSelectioner", "InjectedFileReferenceSelectioner",
      ).map("com.intellij.codeInsight.editorActions.wordSelection." + _) :+
        "com.intellij.ide.highlighter.custom.impl.CustomFileTypeSelectWordHandler"
      for (name <- baseSelectioners) {
        val handler = newPrivate(Class.forName(name))
        selectioners.registerExtension(classOf[ExtendWordSelectionHandler].cast(handler), root)
      }
      // Mod-command intentions copy PSI through the platform preview pipeline.
      // The element type is erased; the only extension registered here is a ModCommandPsiCopyHandler.
      declare("com.intellij.modCommandCopyHandler")
        .asInstanceOf[ExtensionPointImpl[ModCommandService.ModCommandPsiCopyHandler]]
        .registerExtension(new ModCommandService.ModCommandPsiCopyHandler {
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
        if (implementations.isEmpty)
          throw new IllegalStateException(s"no extensions registered for $epName in the Scala plugin's descriptors")
        // A platform extension point (the plugin's own are declared above) is declared on first use.
        val declared = Option(area.getExtensionPointIfRegistered[AnyRef](epName)).getOrElse(declare(epName))
        // The area registers ExtensionPointImpl instances; the element type is erased and AnyRef only names it.
        val ep = declared.asInstanceOf[ExtensionPointImpl[AnyRef]]
        // Class.forName yields Class[?]; a class loaded by name is a reference type.
        implementations.foreach { name =>
          ep.registerExtension(newPrivate(Class.forName(name).asInstanceOf[Class[AnyRef]]), root)
        }
      }
      // Rename: the plugin's processors, in the order its descriptors give (the first that accepts an element
      // renames it), and its veto of elements that have no name to change. Validators of new names: the language's
      // names validator.
      // The area registers ExtensionPointImpl instances; the element type is erased and AnyRef only names it.
      val renameProcessors = declare("com.intellij.renamePsiElementProcessor").asInstanceOf[ExtensionPointImpl[AnyRef]]
      for (bean <- Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.renamePsiElementProcessor")) {
        // A class loaded by name is a reference type.
        val processor = newPrivate(Class.forName(bean.implementation).asInstanceOf[Class[AnyRef]])
        renameProcessors.registerExtension(
          processor, LoadingOrder.readOrder(bean.attributes.getOrElse("order", "")), root)
      }
      // Renaming a reference in a Java source builds the new identifier with the Java tree generator.
      // The element type is erased; AnyRef only names it.
      declare("com.intellij.treeGenerator").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new JavaTreeGenerator, root)
      // Java sources of mixed projects rename with the Java plugin's processors, after the Scala ones that extend them.
      for (name <- Seq("RenameJavaClassProcessor", "RenameJavaMethodProcessor", "RenameJavaVariableProcessor"))
        // A class loaded by name is a reference type.
        renameProcessors.registerExtension(
          newPrivate(Class.forName(s"com.intellij.refactoring.rename.$name").asInstanceOf[Class[AnyRef]]), root)
      declare("com.intellij.renameInputValidator")
      // The element type is erased; AnyRef only names it.
      val renameVetoes = declare("com.intellij.vetoRenameCondition").asInstanceOf[ExtensionPointImpl[AnyRef]]
      for (name <- Descriptors.scalaExtensions(config.scalaPluginJars, "com.intellij.vetoRenameCondition"))
        // A class loaded by name is a reference type.
        renameVetoes.registerExtension(newPrivate(Class.forName(name).asInstanceOf[Class[AnyRef]]), root)
      // The deep inheritors search, which the plugin's overriders search relies on: it walks the direct inheritors
      // that the plugin's searcher gives. (The Java direct searcher reads compiler indices, and would find Java
      // subclasses only.)
      area.getExtensionPoint[QueryExecutor[PsiClass, ClassInheritorsSearch.SearchParameters]](
        "com.intellij.classInheritorsSearch").registerExtension(new JavaClassInheritorsSearcher, root)
      // The references to a Java method, and its overriders in Java classes and in Scala ones (through the
      // inheritors search). The element types are erased; AnyRef only names them.
      area.getExtensionPoint[AnyRef]("com.intellij.methodReferencesSearch")
        .registerExtension(new MethodUsagesSearcher, root)
      area.getExtensionPoint[AnyRef]("com.intellij.overridingMethodsSearch")
        .registerExtension(new JavaOverridingMethodsSearcher, root)
      // The Java rename processors look for the base methods of a method through the super-methods searches.
      CoreApplicationEnvironment.registerExtensionPoint(area, JavaErrorFilter.EP_NAME.getName,
        classOf[JavaErrorFilter])
      // Java's unhandled-exception check asks for exceptions that frameworks handle outside the code; there are none.
      CoreApplicationEnvironment.registerExtensionPoint(area, CustomExceptionHandler.KEY.getName,
        classOf[CustomExceptionHandler])
      // The element type is erased; AnyRef only names it.
      declare("com.intellij.implicitClassSearch").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new ImplicitClassesSearcher, root)
      // The element type is erased; the only extension registered here is a TestSourcesFilter (asSubclass below).
      declare("com.intellij.testSourcesFilter").asInstanceOf[ExtensionPointImpl[TestSourcesFilter]]
        .registerExtension(newPrivate(Class.forName("com.intellij.openapi.roots.ProjectRootTestSourcesFilter")
          .asSubclass(classOf[TestSourcesFilter])), root)
      declare("com.intellij.inspectionElementsMerger")
      declare("com.intellij.globalInspection")
      declare("com.intellij.deadCode")
      declare("com.intellij.implicitUsageProvider")
      appServices.register(classOf[SuppressManager])(
        newPrivate(Class.forName("com.intellij.codeInspection.SuppressManagerImpl")
          .asSubclass(classOf[SuppressManager])))
      // The element types are erased; AnyRef only names them.
      declare("com.intellij.superMethodsSearch").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new MethodSuperSearcher, root)
      declare("com.intellij.deepestSuperMethodsSearch").asInstanceOf[ExtensionPointImpl[AnyRef]]
        .registerExtension(new MethodDeepestSuperSearcher, root)
      // Descriptor beans preserve both the implementation attributes and the platform's id/order semantics.
      val completionPlugin = new DefaultPluginDescriptor(PluginId.getId("org.intellij.scala"))
      val weighers = Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.weigher").filter { bean =>
        ScalaPsiHost.CompletionWeighers(bean.implementation) &&
          Set("completion", "proximity").contains(bean.attributes.getOrElse("key", ""))
      }
      for ((epName, beans) <- Seq(
             "com.intellij.completion.contributor" ->
               (scalaCompletionContributors(config.scalaPluginJars) ++ HostCompletionContributors),
             "com.intellij.weigher" -> weighers)) {
        declare(epName)
        if (beans.isEmpty) throw new IllegalStateException(s"no completion beans for $epName")
        registerCompletionBeans(epName, beans, completionPlugin)
      }
      registerCompletionBeans("com.intellij.completion.contributor", JavaCompletionContributors,
        new DefaultPluginDescriptor(PluginId.getId("com.intellij.java")))
      registerCompletionBeans("com.intellij.weigher", PlatformCompletionWeighers,
        new DefaultPluginDescriptor(PluginId.getId("com.intellij")))
      for (name <- Seq(
             "com.intellij.modcompletion.completionItemProvider", "com.intellij.modcompletion.completionItemFilter"))
        CoreApplicationEnvironment.registerExtensionPoint(area, name, classOf[LanguageExtensionPoint[?]])
      ModCompletionItemProvider.EP_NAME.addExplicitExtension(JavaLanguage.INSTANCE,
        newPrivate(Class.forName("com.intellij.java.completion.modcommand.KeywordCompletionItemProvider")
          .asSubclass(classOf[ModCompletionItemProvider])))
      CoreApplicationEnvironment.registerExtensionPoint(area, JavaTemplateCodeInsightSupport.EP_NAME.getName,
        classOf[JavaTemplateCodeInsightSupport])
      projectServices.register(classOf[AutoPopupControllerHelper])(
        tableProxy(classOf[AutoPopupControllerHelper], "AutoPopupControllerHelper") {
          case ("autoPopupParameterInfoAfterCompletion", _) => CallLog.hit("Completion.popup.parameterInfo"); null
        })
      // These popup requests are optional UI hints; recording intent leaves the synchronous insertion valid without
      // a popup.
      projectServices.register(classOf[AutoPopupController])(new AutoPopupController {
        override def autoPopupParameterInfo(editor: Editor, element: PsiElement): Unit =
          CallLog.hit("Completion.popup.parameterInfo")
        override def scheduleAutoPopup(editor: Editor, kind: CompletionType,
                                      condition: Condition[? >: PsiFile]): Unit =
          CallLog.hit("Completion.popup.completion")
        override def autoPopupMemberLookup(editor: Editor,
                                          condition: Condition[? >: PsiFile]): Unit =
          scheduleAutoPopup(editor, CompletionType.BASIC, condition)
        override def autoPopupMemberLookup(editor: Editor, kind: CompletionType,
                                          condition: Condition[? >: PsiFile]): Unit =
          scheduleAutoPopup(editor, kind, condition)
        /** Unsupported: recorded and rejected by `CallLog`. */
        override def cancelAllRequests(): Unit = CallLog.unsupported("AutoPopupController.cancelAllRequests")
        /** Unsupported: recorded and rejected by `CallLog`. */
        override def waitForDelayedActions(timeout: Long, unit: TimeUnit): Unit =
          CallLog.unsupported("AutoPopupController.waitForDelayedActions")
      })
      appServices.register(classOf[CompletionService])(new Completion.Service)
      // Tab-out scopes are interactive editor state; LSP insertion returns edits and a caret instead.
      appServices.register(classOf[TabOutScopesTracker])(
        tableProxy(classOf[TabOutScopesTracker], "TabOutScopesTracker") {
          case ("registerScopeRange", _) => CallLog.hit("Completion.tabOutScopeSkipped"); null
        })
      // Ranking is deterministic and has no persisted selection history in the host.
      appServices.register(classOf[StatisticsManager])(
        new StatisticsManager {
          override def getUseCount(info: StatisticsInfo): Int = 0
          override def getLastUseRecency(info: StatisticsInfo): Int = Int.MaxValue
          override def getAllValues(context: String): Array[StatisticsInfo] = Array.empty
          override def incUseCount(info: StatisticsInfo): Unit = CallLog.hit("Completion.usageSkipped")
        })
      appServices.register(classOf[Queries])(new QueriesImpl)
      appServices.register(classOf[GistManager])(
        new HostGistManager(() => HostDispatchThread.invokeLater(() => psiChanged())))
      appServices.register(classOf[InternalCompletionSettings])(new InternalCompletionSettings)
      appServices.register(classOf[TailTypeFactory])(new FrontendFriendlyTailTypeFactory)
      // No IDE preference store; editor settings use their own defaults when this optional service is absent.
      appServices.registerNull(classOf[PropertiesComponent])
      // ModuleType.get answers the EMPTY type when this service is absent: the host's modules have no IDE module type.
      appServices.registerNull(classOf[ModuleTypeManager])
      appServices.register(classOf[EditorSettingsExternalizable])(
        new EditorSettingsExternalizable(new EditorSettingsExternalizable.OsSpecificState))
      appServices.register(classOf[CodeInsightSettings])(new CodeInsightSettings)
      appServices.register(classOf[JavaIdeCodeInsightSettings])(new JavaIdeCodeInsightSettings)
      appServices.register(classOf[ScalaCodeInsightSettings])(new ScalaCodeInsightSettings)
      // The IDE shows implicit arguments and conversions while the user toggles them on; an LSP client cannot toggle
      // them from the editor, so they are on until its `scalaAnalyzer.inlayHints.implicits` setting says otherwise.
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
      // Lookup presentation checks test-class icons and Go to Test asks what a test class is: the Scala frameworks
      // that the host can detect (see TestLenses), and no others.
      CoreApplicationEnvironment.registerExtensionPoint(area, TestFramework.EXTENSION_NAME,
        classOf[TestFramework])
      TestLenses.platformFrameworks.foreach(
        area.getExtensionPoint[TestFramework](TestFramework.EXTENSION_NAME.getName).registerExtension(_, root))
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.javaMainMethodProvider",
        classOf[JavaMainMethodProvider])
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.packageDirectoryProvider",
        classOf[PackageDirectoryProvider])
      appServices.register(classOf[TestFrameworks])(newPrivate(classOf[TestFrameworksImpl]))
      // Go to Test: the Java plugin's finder for classes, then the Scala plugin's, which adds objects. The element
      // type is erased; the only extensions registered here are TestFinders.
      val testFinders = declare("com.intellij.testFinder").asInstanceOf[ExtensionPointImpl[TestFinder]]
      testFinders.registerExtension(new JavaTestFinder, root)
      for (name <- Descriptors.scalaExtensions(config.scalaPluginJars, "com.intellij.testFinder"))
        testFinders.registerExtension(newPrivate(Class.forName(name).asSubclass(classOf[TestFinder])), root)
      appServices.registerNull(classOf[NewRdCompletionSupport])
      appServices.register(classOf[GroupedCompletion])(
        tableProxy(classOf[GroupedCompletion], "GroupedCompletion") {
          case ("isEnabled", _) => java.lang.Boolean.FALSE
        })
      // Platform extension points the plugin's PSI queries, with no extensions. Their declarations live in IDE
      // descriptors that are not on this classpath; the platform's own core environment declares them in code too.
      CoreApplicationEnvironment.registerExtensionPoint(area, FileIndentOptionsProvider.EP_NAME,
        classOf[FileIndentOptionsProvider])
      CoreApplicationEnvironment.registerExtensionPoint(area, TreeCopyHandler.EP_NAME, classOf[TreeCopyHandler])
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.multiverseEnabler",
        classOf[MultiverseEnabler])
      // Java PSI of library classes asks for augmentations (Lombok-style); there are none.
      CoreApplicationEnvironment.registerExtensionPoint(area, PsiAugmentProvider.EP_NAME, classOf[PsiAugmentProvider])
      // Which syntax errors are shown: not inside ScalaDoc, and not in files whose errors come from the compiler.
      CoreApplicationEnvironment.registerExtensionPoint(area, HighlightErrorFilter.EP_NAME.getName,
        classOf[HighlightErrorFilter])
      locally {
        val filters = area.getExtensionPoint[HighlightErrorFilter](HighlightErrorFilter.EP_NAME.getName)
        filters.registerExtension(new ScalaDocHighlightErrorFilter, root)
        // The plugin's CompilerHighlightingErrorFilter lives in its compiler-integration module (JPS, compile
        // server), which the host does not load; this is the same rule, decided by the plugin's
        // ScalaHighlightingMode.
        filters.registerExtension(new HighlightErrorFilter {
          override def shouldHighlightErrorElement(element: PsiErrorElement): Boolean =
            !ScalaHighlightingMode.isShowErrorsFromCompilerEnabled(element.getContainingFile)
        }, root)
      }
      // Custom folding regions in comments (`//region` ... `//endregion`, `// <editor-fold>`): the IDE's two
      // providers.
      CoreApplicationEnvironment.registerExtensionPoint(area, CustomFoldingProvider.EP_NAME,
        classOf[CustomFoldingProvider])
      locally {
        val providers = area.getExtensionPoint[CustomFoldingProvider](CustomFoldingProvider.EP_NAME.getName)
        providers.registerExtension(new NetBeansCustomFoldingProvider, root)
        providers.registerExtension(new VisualStudioCustomFoldingProvider, root)
      }
      // Filters of highlighting results (the IDE's hide some in debugger and diff views); the plugin registers none.
      CoreApplicationEnvironment.registerExtensionPoint(area, HighlightInfoFilter.EXTENSION_POINT_NAME,
        classOf[HighlightInfoFilter])
      // Languages that stand for groups of languages in language extensions; none in the host.
      CoreApplicationEnvironment.registerExtensionPoint(area, MetaLanguage.EP_NAME, classOf[MetaLanguage])
      // Smart pointers' anchors and element icons: no providers in the host.
      CoreApplicationEnvironment.registerExtensionPoint(area, SmartPointerAnchorProvider.EP_NAME,
        classOf[SmartPointerAnchorProvider])
      CoreApplicationEnvironment.registerExtensionPoint(area, IconProvider.EXTENSION_POINT_NAME,
        classOf[IconProvider])
      // Java class files consult optional navigation policies before the host's attached-source lookup.
      CoreApplicationEnvironment.registerExtensionPoint(area, ClsCustomNavigationPolicy.EP_NAME,
        classOf[ClsCustomNavigationPolicy])

      appServices.register(classOf[ProgressManager])(new CoreProgressManager)
      appServices.register(classOf[PsiBuilderFactory])(new PsiBuilderFactoryImpl)
      // The builder of the platform's newer syntax layer, which the Java parser uses.
      appServices.register(classOf[PsiSyntaxBuilderFactory])(new PsiSyntaxBuilderFactoryImpl)
      appServices.register(classOf[DefaultASTFactory])(new DefaultASTFactoryImpl)
      // In-memory documents; in the LSP host these become the client's snapshots.
      appServices.register(classOf[FileDocumentManager])(
        // Documents for use off the UI thread (the platform's option for background documents): the host has no UI
        // thread, and its write action keeps writers apart.
        new MockFileDocumentManagerImpl(null, (text: CharSequence) =>
          new DocumentImpl(text, /* forUseInNonAWTThread = */ true)))
      // The sets of files a package scope holds.
      appServices.register(classOf[VirtualFileSetFactory])(new VirtualFileSetFactory {
        override def createCompactVirtualFileSet(): VirtualFileSet = new HostVirtualFileSet
        override def createCompactVirtualFileSet(files: java.util.Collection[? <: VirtualFile]): VirtualFileSet =
          new HostVirtualFileSet(new java.util.LinkedHashSet[VirtualFile](files))
      })
      // Library text defaults to UTF-8, with no per-file charset override.
      appServices.register(classOf[EncodingManager])(new Host_EncodingManager(dispatch {
        case ("EncodingManager.getEncoding", _) => null
        case ("EncodingManager.getDefaultCharset", _) => StandardCharsets.UTF_8
      }))
      // Transformations between a file's bytes and its text presentation, by file type; none for Scala or Java.
      declare(TextPresentationTransformers.Companion.getEP.getName)
      appServices.register(classOf[TextPresentationTransformers])(new TextPresentationTransformers)
      // PSI writes check command state; the core processor tracks it without UI or undo.
      appServices.register(classOf[CommandProcessor])(new HostCommandProcessor)
      // Legacy inspection fixes may prepare writable files only inside their isolated preview session.
      appServices.register(classOf[FileModificationService])(new FileModificationService {
        override def prepareFileForWrite(file: PsiFile): Boolean =
          file != null && IntentionPreviewUtils.isIntentionPreviewActive && IntentionPreviewUtils.isPreviewElement(file)
        override def preparePsiElementsForWrite(elements: java.util.Collection[? <: PsiElement]): Boolean =
          elements.asScala.forall(element => prepareFileForWrite(element.getContainingFile))
        /** Unsupported: recorded and rejected by `CallLog`. */
        override def prepareVirtualFilesForWrite(
            project: Project, files: java.util.Collection[? <: VirtualFile]): Boolean =
          CallLog.unsupported("FileModificationService.prepareVirtualFilesForWrite")
      })
      // PSI versioning (MVCC): the platform's service, which with persistent syntax trees off (the registry default)
      // creates plain PSI elements during reparses.
      appServices.register(classOf[PsiVersioningService])(new PsiVersioningServiceImpl)
      // "Run on the UI thread, carrying the write action": the host has no UI thread and the caller already holds the
      // write action, so the action runs where it is.
      appServices.register(classOf[TransferredWriteActionService])(
        tableProxy(classOf[TransferredWriteActionService], "TransferredWriteActionService") {
          case ("runOnEdtWithTransferredWriteActionAndWait" |
                "runOnBackgroundThreadWithTransferredWriteActionAndWait", Array(r: Runnable)) =>
            r.run()
            null
        })
      // Document commits: synchronous, on the editing thread.
      appServices.register(classOf[DocumentCommitProcessor])(new HostDocumentCommitProcessor)
      // Read through serviceOrNull: PSI MVCC is off by default (registry psi.enable.persistent.syntax.tree=false).
      appServices.registerNull(classOf[PsiVersioningGarbageCollector])
      // Optional diagnostics hook; the caller null-checks it.
      appServices.registerNull(classOf[ParsingDiagnostics.ParserDiagnosticsHandler])
      appServices.register(classOf[AsyncExecutionService])(new HostAsyncExecutionService)
      // Private constructors: the IDE container instantiates these reflectively, and so does the host.
      appServices.register(classOf[LanguageSubstitutors])(newPrivate(classOf[LanguageSubstitutors]))
      // Holds one flag (external change in progress) that PSI events read.
      locally {
        // A class loaded by name is a reference type.
        val cls = Class.forName("com.intellij.psi.ExternalChangeActionUtil$PsiExternalChangeService")
          .asInstanceOf[Class[AnyRef]]
        appServices.register(cls)(newPrivate(cls))
      }
      // The plugin's settings provider links UI classes absent from this classpath. Its customizeDefaults sets
      // these three indent sizes to 2 and KEEP_FIRST_COLUMN_COMMENT to false; the settings bean owns import defaults.
      val scalaStyleProvider = tableProxy(classOf[LanguageCodeStyleProvider], "ScalaCodeStyleProvider") {
        case ("getLanguage", _) => ScalaLanguage.INSTANCE
        case ("getDefaultCommonSettings", _) =>
          val ScalaDefaultIndentSize = 2
          val common = new CommonCodeStyleSettings(ScalaLanguage.INSTANCE)
          val indent = common.initIndentOptions()
          indent.INDENT_SIZE = ScalaDefaultIndentSize
          indent.TAB_SIZE = ScalaDefaultIndentSize
          indent.CONTINUATION_INDENT_SIZE = ScalaDefaultIndentSize
          common.KEEP_FIRST_COLUMN_COMMENT = false
          common
        case ("getDocCommentSettings", _) => DocCommentSettings.DEFAULTS
        case ("createCustomSettings", Array(settings: CodeStyleSettings)) =>
          new ScalaCodeStyleSettings(settings)
      }
      val javaStyleProvider = tableProxy(classOf[LanguageCodeStyleProvider], "JavaCodeStyleProvider") {
        case ("getLanguage", _) => JavaLanguage.INSTANCE
        case ("getDefaultCommonSettings", _) =>
          val common = new CommonCodeStyleSettings(JavaLanguage.INSTANCE)
          common.initIndentOptions()
          common
        case ("getDocCommentSettings", _) => DocCommentSettings.DEFAULTS
        case ("createCustomSettings", Array(settings: CodeStyleSettings)) =>
          new JavaCodeStyleSettings(settings)
      }
      appServices.register(classOf[CodeStyleSettingsService])(
        tableProxy(classOf[CodeStyleSettingsService], "CodeStyleSettingsService") {
          case ("addListener", _) => null
          case ("getFileTypeIndentOptionsFactories", _) => java.util.List.of()
          case ("getLanguageCodeStyleProviders", _) =>
            java.util.List.of(scalaStyleProvider, javaStyleProvider)
          // Language settings beans with defaults, without their settings-UI providers.
          case ("getCustomCodeStyleSettingsFactories", _) =>
            java.util.List.of(new CustomCodeStyleSettingsFactory {
              override def createCustomSettings(s: CodeStyleSettings) =
                new ScalaCodeStyleSettings(s)
            }, new CustomCodeStyleSettingsFactory {
              override def createCustomSettings(s: CodeStyleSettings) =
                new JavaCodeStyleSettings(s)
            })
        })
      appServices.register(classOf[Formatter])(new FormatterImpl)
      appServices.register(classOf[FormattingProgressCallbackFactory])(
        tableProxy(classOf[FormattingProgressCallbackFactory], "FormattingProgressCallbackFactory") {
          // The optional UI callback is absent; cancellation uses the request's progress indicator.
          case ("createProgressCallback", _) => null
        })
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.lang.formatter",
        classOf[LanguageExtensionPoint[?]])
      LanguageFormatting.INSTANCE.addExplicitExtension(ScalaLanguage.INSTANCE, new ScalaFormattingModelBuilder)
      LanguageFormatting.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaFormattingModelBuilder)
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.lang.importOptimizer",
        classOf[LanguageExtensionPoint[?]])
      LanguageImportStatements.INSTANCE.addExplicitExtension(ScalaLanguage.INSTANCE, new ScalaImportOptimizer)
      for (name <- Seq(
             "com.intellij.formattingService", "com.intellij.lang.formatter.restriction",
             "com.intellij.externalFormatProcessor", "com.intellij.preFormatProcessor",
             "com.intellij.postFormatProcessor", "com.intellij.disabledIndentRangesProvider",
             "com.intellij.documentWriteAccessGuard")) declare(name)
      area.getExtensionPoint[FormattingService]("com.intellij.formattingService")
        .registerExtension(new CoreFormattingService, root)
      area.getExtensionPoint[LanguageFormattingRestriction]("com.intellij.lang.formatter.restriction")
        .registerExtension(new ScalaLanguageFormattingRestriction, root)
      area.getExtensionPoint[PreFormatProcessor]("com.intellij.preFormatProcessor")
        .registerExtension(new ScalaDocNewlinedPreFormatProcessor, root)
      val postProcessors = area.getExtensionPoint[PostFormatProcessor]("com.intellij.postFormatProcessor")
      postProcessors.registerExtension(new ScalaBracePostFormatProcessor, root)
      postProcessors.registerExtension(new ScalaTrailingCommaProcessor, root)
      val symbolIndex = this.symbolIndex
      appServices.register(classOf[StubIndex])(symbolIndex.stubIndex)
      // The project library table, which the host never changes; LibraryExtensionsManager subscribes to it.
      lazy val libraryTable = tableProxy(classOf[LibraryTable], "LibraryTable") {
        case ("addListener", _) => null
      }
      appServices.register(classOf[LibraryTablesRegistrar])(new Host_LibraryTablesRegistrar(dispatch {
        case ("LibraryTablesRegistrar.getLibraryTable", Array(_: Project)) => libraryTable
      }))
      // Plugin application services: plain classes.
      appServices.register(classOf[ScalaApplicationSettings])(new ScalaApplicationSettings)
      // Which regions the folding builder marks as collapsed by default: the IDE's defaults.
      appServices.register(classOf[ScalaCodeFoldingSettings])(new ScalaCodeFoldingSettingsImpl)
      appServices.register(classOf[ExpectedTypes])(new ExpectedTypesImpl)
      appServices.register(classOf[ScalaMetaApi])(new ScalaMetaApiImpl)
      // The editor's read/write-action facade, reached from CodeStyle.getSettings: the host's callers may always read.
      appServices.register(classOf[Elf])(tableProxy(classOf[Elf], "Elf") {
        // Kotlin's Function0 is written in full because it would clash with scala.Function0. Its result is a Java
        // generic `?`: always a reference at runtime.
        case ("runReadAction", Array(f: kotlin.jvm.functions.Function0[?])) =>
          HostLock.readLocked(f.invoke()).asInstanceOf[AnyRef]
        // Documents are plain platform documents, outside the editor's Elf scope.
        case ("isInElfScope", _) => java.lang.Boolean.FALSE
      })
      // Annotations fetch the global color scheme for presentation (inlay hints, error-stripe-only attributes). The
      // host presents nothing: every key has empty attributes.
      lazy val noColors = tableProxy(classOf[EditorColorsScheme], "EditorColorsScheme") {
        case ("getAttributes", _) => new TextAttributes()
      }
      appServices.register(classOf[EditorColorsManager])(new Host_EditorColorsManager(dispatch {
        case ("EditorColorsManager.getGlobalScheme", _) => noColors
      }))
      // A key's own defaults, which code blocks in quick documentation fall back to: empty as well.
      appServices.register(classOf[TextAttributesKey.TextAttributeKeyDefaultsProvider])(
        _ => new TextAttributes())
      // Plugin attribution of logged platform errors (e.g. an annotation range outside its element); the host does not
      // track plugins, so the exception names none.
      appServices.register(classOf[PluginProblemReporter])(
        tableProxy(classOf[PluginProblemReporter], "PluginProblemReporter") {
          case ("createPluginExceptionByClass", Array(message: String, cause, _)) =>
            // The proxied method's second parameter is the Throwable cause; the pattern leaves it untyped.
            new PluginException(message, cause.asInstanceOf[Throwable], null)
        })
      // Usage statistics (FUS), which the plugin logs while highlighting: turned off. Building an event would create a
      // device id in the user's Java preferences, which the user's IntelliJ installations share. Every recorder gets
      // no providers, so event logging has nothing to log to and builds no event. The provider map is private platform
      // state; if it changes, this fails at startup.
      locally {
        type Providers = java.util.Collection[StatisticsEventLoggerProvider]
        val holder = new StatisticsEventLogProvidersHolder(
          CoroutineScopeKt.CoroutineScope(EmptyCoroutineContext.INSTANCE))
        val noProviders = new java.util.AbstractMap[String, Providers] {
          override def get(recorder: Any): Providers = java.util.List.of()
          override def entrySet(): java.util.Set[java.util.Map.Entry[String, Providers]] = java.util.Set.of()
        }
        val field = classOf[StatisticsEventLogProvidersHolder].getDeclaredField("eventLoggerProvidersExt")
        field.setAccessible(true)
        // The private field holds an AtomicReference to the provider map; a different type fails here at startup.
        field.get(holder).asInstanceOf[AtomicReference[java.util.Map[String, Providers]]].set(noProviders)
        appServices.register(classOf[StatisticsEventLogProvidersHolder])(holder)
      }
      // Write-safety checks of PSI change notifications: they only check the UI thread, which the host never runs on.
      appServices.register(classOf[TransactionGuard])(new TransactionGuardImpl)
      // Annotations carry their quick fixes, which the platform wraps as intentions when the annotation is created.
      // The batch executor applies textual mod commands to preview copies without the desktop executor.
      appServices.register(classOf[ModCommandExecutor])(new HostModCommandExecutor)
      appServices.register(classOf[ModCommandService])(new ModCommandServiceImpl)
      // Interpolation references give completion its Scala prefix inside plain strings; file paths and URL providers
      // need IDE navigation.
      appServices.register(classOf[PsiReferenceService])(new PsiReferenceServiceImpl)
      appServices.register(classOf[ReferenceProvidersRegistry]) {
        val registry = new ReferenceProvidersRegistryImpl
        val provider = newPrivate(
          Class.forName("org.jetbrains.plugins.scala.lang.references.InterpolatedStringReferenceProvider")
            .asSubclass(classOf[PsiReferenceProvider]))
        registry.getRegistrar(ScalaLanguage.INSTANCE).registerReferenceProvider(
          PlatformPatterns.psiElement(classOf[ScStringLiteral]),
          provider, PsiReferenceRegistrar.DEFAULT_PRIORITY, root)
        registry
      }
      // Host module metadata lives in ModuleModel; there are no IDE workspace entities or manifest overrides.
      projectServices.register(classOf[WorkspaceModel])(
        tableProxy(classOf[WorkspaceModel], "WorkspaceModel") {
          case ("getCurrentSnapshot", _) => ImmutableEntityStorage.Companion.empty()
        })
      projectServices.register(classOf[GuessManager])(new GuessManagerImpl(project))
      // Problem descriptors for the local inspections and their suppress actions; the host has no global inspection run.
      projectServices.register(classOf[InspectionManager])(new InspectionManagerBase(project) {
        override def createNewGlobalContext(): GlobalInspectionContext = CallLog.unsupported("InspectionManager.globalContext")
        override def createNewGlobalContext(reuse: Boolean): GlobalInspectionContext = createNewGlobalContext()
      })
      // Comments and whitespace built from text, as the suppress actions write `//noinspection`.
      projectServices.register(classOf[PsiParserFacade])(new PsiParserFacadeImpl(project))
      projectServices.register(classOf[ControlFlowFactory]) {
        val factory = new ControlFlowFactory(project)
        Disposer.register(root, factory)
        factory
      }
      appServices.register(classOf[JdkApiCompatibilityService])(new JdkApiCompatibilityService)
      appServices.register(classOf[JavaVersionService])(new JavaVersionServiceImpl)
      // Java module (JPMS) accessibility: the host has no module-info graph (its modules come from the build), so the
      // platform's graph-less implementation, which treats every class as accessible.
      appServices.register(classOf[JavaModuleGraphHelper])(new DumbJavaModuleGraphHelper)
      // Platform library implementations used by Java PSI of library classes and by PSI copies.
      appServices.register(classOf[JavaClassSupers])(new JavaClassSupersImpl)
      appServices.register(classOf[PsiSubstitutorFactory])(new PsiSubstitutorFactoryImpl)
      appServices.register(classOf[PsiPackageImplementationHelper])(new CorePsiPackageImplementationHelper)
      appServices.register(classOf[IndentHelper])(new IndentHelperImpl)
    }

    /** Registers language implementations and library/index services after the root extension area exists. */
    private def installLanguageRegistries(): Unit = {
      /** Converts IntelliJ's wildcard query arguments to the host's typed word-index predicate. */
      def wordQuery(words: java.util.Collection[?], checker: Condition[? >: Integer]): WordQuery =
        // The keys of the word index (the only index queried here) are IdIndexEntry.
        WordQuery(words.asScala.map(_.asInstanceOf[IdIndexEntry]).toSeq, mask => checker == null || checker.value(mask))

      // ---- 2. Language registries and stub schema: explicit extensions only ---------------------
      val parserDefinitions: Seq[ParserDefinition] = Seq(
        new ScalaParserDefinition,
        new Scala3ParserDefinition,
        new ScalaDocParserDefinition,
        new ScalaDirectiveParserDefinition,
        // The contents of ScalaDoc links ([[scala.Option]]), parsed lazily inside doc comments.
        new ScalaDocRefLinkParserDefinition,
      )
      parserDefinitions.foreach { pd =>
        LanguageParserDefinitions.INSTANCE.addExplicitExtension(pd.getFileNodeType.getLanguage, pd)
      }
      // Word selection uses the same layered lexer as a Scala editor, including string escapes and ScalaDoc.
      declare(SyntaxHighlighterLanguageFactory.EP_NAME.getName)
      SyntaxHighlighterFactory.LANGUAGE_FACTORY.addExplicitExtension(ScalaLanguage.INSTANCE,
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
        new JavaSyntaxHighlighterFactory)
      LanguageCommenters.INSTANCE.addExplicitExtension(ScalaLanguage.INSTANCE, new ScalaCommenter)
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
        /** Unsupported: recorded and rejected by `CallLog`. */
        override protected def getDefault(id: String): AnyRef = CallLog.unsupported(s"AdvancedSettings.getDefault $id")
        /** Unsupported: recorded and rejected by `CallLog`. */
        override def setSetting(id: String, value: Any, kind: AdvancedSettingType): Unit =
          CallLog.unsupported(s"AdvancedSettings.setSetting $id")
      })
      // Parameter info for arguments, type arguments and patterns: the plugin's handlers, as its descriptors register
      // them.
      for (bean <- Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.codeInsight.parameterInfo")
           if bean.attributes.get("language").contains("Scala")) {
        // The descriptors register these beans under the parameter-info extension point, whose implementations are
        // ParameterInfoHandlers.
        val handler = newPrivate(Class.forName(bean.implementation)).asInstanceOf[ParameterInfoHandler[?, ?]]
        LanguageParameterInfo.INSTANCE.addExplicitExtension(ScalaLanguage.INSTANCE, handler)
      }
      // beforeCompletion and lookup escaping must use Scala's operators and keywords, rather than the Java fallback.
      LanguageNamesValidation.INSTANCE.addExplicitExtension(ScalaLanguage.INSTANCE, new ScalaNamesValidator)
      // Java sources of mixed projects.
      LanguageParserDefinitions.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaParserDefinition)
      // Java's typed leaves (PsiIdentifier, PsiKeyword, ...): Java PSI casts its tokens to them.
      LanguageASTFactory.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaASTFactory)
      // The Scala filter requires a Java provider. Java's real provider fails to construct without JavaBundle;
      // its default exclusions are unavailable, so Java calls can show names the IDE would exclude.
      InlayParameterHintsExtension.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE,
        new InlayParameterHintsProvider {
          override def getDefaultBlackList: JavaSet[String] = JavaSet.of()
        })
      // Java classes and fields describe themselves through these (getPresentation): completion shows a library class
      // after `new` with its package.
      ItemPresentationProviders.INSTANCE.addExplicitExtension(classOf[PsiClass], new ClassPresentationProvider)
      ItemPresentationProviders.INSTANCE.addExplicitExtension(classOf[PsiField], new FieldPresentationProvider)
      LanguageSyntaxDefinitions.getINSTANCE.addExplicitExtension(
        JavaLanguage.INSTANCE, new JavaSyntaxDefinitionExtension)
      ElementTypeConverters.getInstance.addExplicitExtension(
        JavaLanguage.INSTANCE, new JavaElementTypeConverterExtension)
      // Tokens every language shares (whitespace, bad characters): the converters of a language are all those
      // registered for it, so the common one is registered for Java too.
      ElementTypeConverters.getInstance.addExplicitExtension(
        JavaLanguage.INSTANCE, new CommonElementTypeConverterFactory)
      val scalaLang = ScalaLanguage.INSTANCE
      val scala3Lang = Scala3Language.INSTANCE
      LanguageASTFactory.INSTANCE.addExplicitExtension(scalaLang, new ScalaASTFactory)
      // A .scala file of a Scala 3 module is Scala 3 (the plugin's substitutor reads the file's module).
      LanguageSubstitutors.getInstance.addExplicitExtension(scalaLang, new ScalaLanguageSubstitutor)
      val viewProviderFactory = new ScFileViewProviderFactory
      LanguageFileViewProviders.INSTANCE.addExplicitExtension(scalaLang, viewProviderFactory)
      LanguageFileViewProviders.INSTANCE.addExplicitExtension(scala3Lang, viewProviderFactory)

      // Stub schema (model): the plugin's serializer/factory registrations and language stub definitions.
      val scalaPlugin = new DefaultPluginDescriptor(PluginId.getId("org.intellij.scala"))

      /** Associates a language extension with the Scala plugin that owns it. */
      def languageBean[T <: AnyRef](language: String, extension: T): LanguageExtensionPoint[T] = {
        val bean = new LanguageExtensionPoint[T](language, extension)
        bean.setPluginDescriptor(scalaPlugin)
        bean
      }

      // The element type is erased; the only extensions registered here are StubRegistryExtensions.
      val declaredStubRegistry =
        declare("com.intellij.stubElementRegistryExtension").asInstanceOf[ExtensionPointImpl[StubRegistryExtension]]
      declaredStubRegistry.registerExtension(new ScalaStubRegistryExtension, root)
      declaredStubRegistry.registerExtension(new JavaStubRegistryExtension, root)
      // Element type <-> PSI class mapping the Java stub registry fills (private constructor, as a container service).
      appServices.register(classOf[JavaStubElementTypePsiElementMappingRegistry])(
        newPrivate(classOf[JavaStubElementTypePsiElementMappingRegistry]))
      // The element type is erased; the only extensions registered here come from `languageBean`.
      val stubDefinitions = declare("com.intellij.languageStubDefinition")
        .asInstanceOf[ExtensionPointImpl[LanguageExtensionPoint[LanguageStubDefinition]]]
      stubDefinitions.registerExtension(languageBean("Scala", new ScalaLanguageStubDefinition), scalaPlugin, root)
      stubDefinitions.registerExtension(languageBean("Scala 3", new Scala3LanguageStubDefinition), scalaPlugin, root)
      stubDefinitions.registerExtension(
        languageBean(JavaLanguage.INSTANCE.getID, new JavaStubDefinition), scalaPlugin, root)
      // Split/join list: the platform's intentions ask for the list contexts of the file's language. Flip comma asks for
      // language flippers, which the Scala plugin declares none of.
      // The element type is erased; the only extensions registered here are the Scala plugin's list contexts.
      val splitJoinContexts = declare("com.intellij.listSplitJoinContext")
        .asInstanceOf[ExtensionPointImpl[LanguageExtensionPoint[ListSplitJoinContext]]]
      for (bean <- Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.listSplitJoinContext")
           if bean.attributes.get("language").contains("Scala")) {
        val context = newPrivate(Class.forName(bean.implementation).asSubclass(classOf[ListSplitJoinContext]))
        splitJoinContexts.registerExtension(languageBean(ScalaLanguage.INSTANCE.getID, context), scalaPlugin, root)
      }
      declare("com.intellij.flipCommaIntention.flipper")
      appServices.register(classOf[StubElementRegistryService])(
        new StubElementRegistryServiceImpl(CoroutineScopeKt.CoroutineScope(EmptyCoroutineContext.INSTANCE)))

      // Compiler statistics need IDE-built indexes; live argument templates need an interactive editor.
      val javaRegistryKeys = Seq(
        "compiler.ref.index" -> "Compiler reference index",
        "ide.java.completion.suggest.static.after.instance" -> "Suggest static members after instance qualifiers",
        "java.completion.argument.live.template" -> "Interactive argument templates",
        "java.completion.show.constructors" -> "Separate constructor suggestions",
      ).map { (key, description) => key -> new RegistryKeyDescriptor(
        key, description, "false", false, false, "com.intellij.java", null) }.toMap
      val highestJava = "java.highest.language.level"
      val javaRelease = new RegistryKeyDescriptor(highestJava,
        "Highest Java level supported by the host", LanguageLevel.HIGHEST.toJavaVersion.feature.toString,
        false, false, "com.intellij.java", null)
      Registry.Companion.setContributedKeys(
        (Descriptors.scalaRegistryKeys(getClass.getClassLoader) ++ javaRegistryKeys + (highestJava -> javaRelease))
          .asJava)
      Registry.Companion.markAsLoaded()

      val fileTypes = new CoreFileTypeRegistry
      fileTypes.registerFileType(ScalaFileType.INSTANCE, "scala")
      fileTypes.registerFileType(JavaFileType.INSTANCE, "java")
      fileTypes.registerFileType(JavaClassFileType.INSTANCE, "class")
      // Scala 3 library classes: the .tasty file is decompiled into Scala 3 PSI, its .class sibling is hidden.
      fileTypes.registerFileType(TastyFileType, "tasty")
      // View providers of binary library files, by file type, as the IDE registers them.
      FileTypeFileViewProviders.INSTANCE.addExplicitExtension(
        JavaClassFileType.INSTANCE, new ClassFileViewProviderFactory)
      FileTypeFileViewProviders.INSTANCE.addExplicitExtension(
        TastyFileType, new TastyFileViewProviderFactory)
      // The registry is also the FileTypeManager: FileTypeManager.getInstance falls back to an empty manager (which
      // answers a mock file type for everything) when the registry is not one. Lookups go to the core registry above.
      val fileTypeManager = new Host_FileTypeManager(dispatch {
        case ("FileTypeManager.getFileTypeByFileName", Array(name: String)) => fileTypes.getFileTypeByFileName(name)
        case ("FileTypeManager.getFileTypeByExtension", Array(extension: String)) =>
          fileTypes.getFileTypeByExtension(extension)
        case ("FileTypeManager.getFileTypeByFile", Array(f: VirtualFile)) => fileTypes.getFileTypeByFile(f)
        case ("FileTypeManager.findFileTypeByName", Array(name: String)) => fileTypes.findFileTypeByName(name)
        case ("FileTypeManager.getRegisteredFileTypes", _) => fileTypes.getRegisteredFileTypes
        // Files come from the build's source roots and library jars; none is ignored.
        case ("FileTypeManager.isFileIgnored", _) => java.lang.Boolean.FALSE
      })
      FileTypeRegistry.setInstanceSupplier(() => fileTypeManager, root)

      // Class files become PSI as in the IDE: the first decompiler that accepts the file wins; the plugin's comes
      // before the platform's Java decompiler.
      // The element type is erased; the only extensions registered here are class file Decompilers.
      val decompilers = declare("com.intellij.psi.classFileDecompiler")
        .asInstanceOf[ExtensionPointImpl[ClassFileDecompilers.Decompiler]]
      decompilers.registerExtension(new ScClassFileDecompiler, root)
      decompilers.registerExtension(new ClsDecompilerImpl, root)
      appServices.register(classOf[ClassFileDecompilers])(newPrivate(classOf[ClassFileDecompilers]))
      // Stubs of library class files are built on demand from their bytes (the core loader kotlinc uses), through the
      // platform's class-file stub builder, which defers to the same decompilers.
      BinaryFileStubBuilders.INSTANCE.addExplicitExtension(
        TastyFileType, new TastyFileStubBuilder)
      BinaryFileStubBuilders.INSTANCE.addExplicitExtension(
        JavaClassFileType.INSTANCE, new ClassFileStubBuilder)
      appServices.register(classOf[StubTreeLoader])(new CoreStubTreeLoader)
      // A binary library file's text (its document, which Java class files' PSI mirrors): decompiled as the IDE
      // registers it, Java class files by the platform's decompiler, TASTy files by the plugin's.
      declare("com.intellij.filetype.decompiler")
      appServices.register(classOf[BinaryFileTypeDecompilers])(newPrivate(classOf[BinaryFileTypeDecompilers]))
      BinaryFileTypeDecompilers.getInstance.addExplicitExtension(
        JavaClassFileType.INSTANCE, new ClassFileDecompiler)
      BinaryFileTypeDecompilers.getInstance.addExplicitExtension(
        TastyFileType, new TastyDecompiler)
      // File properties the IDE pushes to files (language levels), which stub builders read to version their stubs; the
      // host pushes none.
      appServices.register(classOf[PushedFilePropertiesRetriever])(
        tableProxy(classOf[PushedFilePropertiesRetriever], "PushedFilePropertiesRetriever") {
          case ("dumpSortedPushedProperties", _) => java.util.List.of()
        })
      val libraryFiles = this.libraryFiles
      // The platform's file-based indexes: the file-name index, answered from library roots, and the word index of
      // project sources, which the reference search reads to find the files that mention a name.
      appServices.register(classOf[FileBasedIndex])(new Host_FileBasedIndex(dispatch {
        case ("FileBasedIndex.getValues", Array(id, qualifier: String, scope: GlobalSearchScope))
            if id == ImportOrderingIndexer.Id =>
          importIndex.values(qualifier, scope)
        case ("FileBasedIndex.processFilesContainingAnyKey",
              Array(id: ID[?, ?], names: java.util.Collection[?], scope: GlobalSearchScope, _, _,
                    processor: Processor[?])) if id == FilenameIndex.NAME =>
          // The platform asks this method for a processor of VirtualFiles.
          val p = processor.asInstanceOf[Processor[VirtualFile]]
          val matching = names.asScala.toSeq.flatMap(n => libraryFiles.filesNamed(n.toString)).filter(scope.contains)
          java.lang.Boolean.valueOf(matching.forall(p.process))
        case ("FileBasedIndex.getContainingFiles", Array(id, word: IdIndexEntry, scope: GlobalSearchScope))
            if id == IdIndex.NAME =>
          wordIndex.filesWith(word, scope).asJava
        case ("FileBasedIndex.processValues",
              Array(id, word: IdIndexEntry, inFile, processor: FileBasedIndex.ValueProcessor[?],
                    scope: GlobalSearchScope)) if id == IdIndex.NAME =>
          // The values of the IdIndex (the only index answered here) are Integer masks.
          val p = processor.asInstanceOf[FileBasedIndex.ValueProcessor[Integer]]
          val files = inFile match {
            case file: VirtualFile => Seq(file).filter(scope.contains)
            case _ => wordIndex.filesWith(word, scope)
          }
          java.lang.Boolean.valueOf(
            files.forall(file => wordIndex.occurrences(word, file).forall(mask => p.process(file, mask))))
        case ("FileBasedIndex.processFilesContainingAllKeys",
              Array(id, words: java.util.Collection[?], scope: GlobalSearchScope, checker, processor: Processor[?]))
            if id == IdIndex.NAME =>
          // The platform asks this method for a processor of VirtualFiles; the IdIndex's value checker takes the
          // Integer masks of its values.
          val p = processor.asInstanceOf[Processor[VirtualFile]]
          val query = wordQuery(words, checker.asInstanceOf[Condition[Integer]])
          java.lang.Boolean.valueOf(wordIndex.filesWithAll(Seq(query), scope).forall(p.process))
        // The reference search asks for several indexes at once; the host has the word index only.
        case ("FileBasedIndex.processFilesContainingAllKeys",
              Array(queries: java.util.Collection[?], scope: GlobalSearchScope, processor: Processor[?])) =>
          // The platform asks this method for a processor of VirtualFiles.
          val p = processor.asInstanceOf[Processor[VirtualFile]]
          val wordQueries = queries.asScala.toSeq.map {
            case query: FileBasedIndex.AllKeysQuery[?, ?] if query.getIndexId == IdIndex.NAME =>
              // The IdIndex's value checker takes the Integer masks of its values.
              wordQuery(query.getDataKeys, query.getValueChecker.asInstanceOf[Condition[Integer]])
            case other => CallLog.unsupported(s"FileBasedIndex.processFilesContainingAllKeys over the index of $other")
          }
          java.lang.Boolean.valueOf(wordIndex.filesWithAll(wordQueries, scope).forall(p.process))
      }))
      // How files are split into words for that index: by language (the plugin's and Java's words scanners; Java's has
      // its own indexer), and no file type outside a language has one.
      appServices.register(classOf[CacheBuilderRegistry])(new CacheBuilderRegistry {
        override def getCacheBuilder(fileType: FileType): WordsScanner = null
      })
      LanguageFindUsages.INSTANCE.addExplicitExtension(ScalaLanguage.INSTANCE, new ScalaFindUsagesProvider)
      LanguageFindUsages.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaFindUsagesProvider)
      IdIndexers.INSTANCE.addExplicitExtension(JavaFileType.INSTANCE, new JavaIdIndexer)
      // Find usages searches the project scope by default; the host offers no other predefined scopes.
      appServices.register(classOf[FindUsagesSettings])(new FindUsagesSettingsImpl)
      appServices.register(classOf[PredefinedSearchScopeProvider])(new PredefinedSearchScopeProvider {
        override def getPredefinedScopes(project: Project, dataContext: DataContext, suggestSearchInLibs: Boolean,
                                         prevSearchFiles: Boolean, currentSelection: Boolean, usageView: Boolean,
                                         showEmptyScopes: Boolean) =
          java.util.List.of()
      })
      // The element a position stands for, as the editor finds it: the plugin's evaluator decides for Scala.
      declare("com.intellij.targetElementUtilExtender")
      appServices.register(classOf[TargetElementUtil])(new TargetElementUtil)
      TargetElementUtilBase.TARGET_ELEMENT_EVALUATOR.addExplicitExtension(ScalaLanguage.INSTANCE,
        new ScalaTargetElementEvaluator)
      // Extension points the reference search and the go-to-class contributor read, with no extensions in the host.
      for (name <- Seq(
             "com.intellij.useScopeOptimizer", "com.intellij.codeUsageScopeOptimizer",
             "com.intellij.searchCandidateBatcher", "com.intellij.pom.declarationSearcher",
             "com.intellij.codeInsight.containerProvider",
             "com.intellij.gotoByName.defaultProvider.ignoreLanguage")) declare(name)
      // Go to implementation: the platform's searches of a class's inheritors and a method's overriders, which also
      // ask for functional expressions (lambdas, method references) of the Java plugin's search, with no searcher in
      // the host.
      declare("com.intellij.functionalExpressionSearch")
      declare("com.intellij.definitionsScopedSearch")
      locally {
        val searchers = area.getExtensionPoint[QueryExecutor[PsiElement, DefinitionsScopedSearch.SearchParameters]](
          "com.intellij.definitionsScopedSearch")
        searchers.registerExtension(new ClassImplementationsSearch, root)
        searchers.registerExtension(new MethodImplementationsSearch, root)
      }
      // The platform's reference searcher: it scans the files the word index names for an element's name. (The IDE's
      // SearcherQueryExecutor, for searches by symbol, is left out: no Scala element is one.)
      area.getExtensionPoint[QueryExecutor[PsiReference, ReferencesSearch.SearchParameters]](
        "com.intellij.referencesSearch").registerExtension(new CachesBasedRefSearcher, root)
      // The search helper's file scans run one after the other, as in the platform's core environment: requests
      // already hold the host's read lock.
      appServices.register(classOf[JobLauncher])(new CoreJobLauncher)
      // The host is never in dumb mode: indexes are usable.
      appServices.register(classOf[DumbUtil])(
        tableProxy(classOf[DumbUtil], "DumbUtil") {
          case ("mayUseIndices", _) => java.lang.Boolean.TRUE
        })
      // Persistent file attributes: the plugin caches "is this class file Scala?" in them. The host persists nothing,
      // so every read misses (the plugin recomputes) and writes are discarded.
      appServices.register(classOf[ManagingFS])(new Host_ManagingFS(dispatch {
        case ("ManagingFS.readAttribute", _) => null
        case ("ManagingFS.writeAttribute", _) =>
          new AttributeOutputStream(new BufferExposingByteArrayOutputStream()) {
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
      val fileIndex = tableProxy(classOf[ProjectFileIndex], "ProjectFileIndex") {
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
        case ("isInSourceContent" | "isInContent", Array(f: VirtualFile)) =>
          java.lang.Boolean.valueOf(!fileStore.isLibrary(f))
        // Sources: the project's and the libraries' attached ones.
        case ("isInSource", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(!fileStore.isLibraryClass(f))
        case ("isInTestSourceContent", _) => java.lang.Boolean.FALSE
        // Every source file the host was given is a production source; the plugin's filter scopes ask by root type.
        case ("isUnderSourceRootOfType", Array(f: VirtualFile, types: java.util.Set[?])) =>
          java.lang.Boolean.valueOf(!fileStore.isLibrary(f) && types.contains(JavaSourceRootType.SOURCE))
      }
      projectServices.register(classOf[ProjectFileIndex])(fileIndex)
      // Java stub eligibility reads a persisted level as the source-content marker. The host owns source membership,
      // and uses the same highest Java level here as in JavaPsiImplementationHelper, without persisting VFS attributes.
      appServices.register(classOf[InternalPersistentJavaLanguageLevelReaderService])(
        tableProxy(classOf[InternalPersistentJavaLanguageLevelReaderService], "JavaLanguageLevelReader") {
          case ("getPersistedLanguageLevel", Array(f: VirtualFile)) =>
            if (moduleModel.ofFile(f).isDefined) LanguageLevel.HIGHEST else null
        })
      // The same answers through the platform's facade (ClassFileViewProvider asks it before building Java PSI).
      projectServices.register(classOf[FileIndexFacade])(new Host_FileIndexFacade(project, dispatch {
        case ("FileIndexFacade.isInLibraryClasses", Array(f: VirtualFile)) =>
          java.lang.Boolean.valueOf(fileStore.isLibraryClass(f))
        case ("FileIndexFacade.isInLibrary", Array(f: VirtualFile)) => java.lang.Boolean.valueOf(fileStore.isLibrary(f))
        case ("FileIndexFacade.isInLibrarySource", Array(f: VirtualFile)) =>
          java.lang.Boolean.valueOf(fileStore.isLibrarySource(f))
        // Roots change only when a library or the JDK is mounted, which also counts as a PSI change.
        case ("FileIndexFacade.getRootModificationTracker", _) => psiModTracker
        case ("FileIndexFacade.getModuleForFile", Array(f: VirtualFile)) => moduleModel.ofFile(f).map(_.module).orNull
        case ("FileIndexFacade.isInSourceContent" | "FileIndexFacade.isInContent", Array(f: VirtualFile)) =>
          java.lang.Boolean.valueOf(!fileStore.isLibrary(f))
        case ("FileIndexFacade.isInSource", Array(f: VirtualFile)) =>
          java.lang.Boolean.valueOf(!fileStore.isLibraryClass(f))
      }))
      // Tree changes reach the plugin's listener, which decides which of its caches an edit invalidates.
      val psiManager = new HostPsiManager(project, psiModTracker, fileManager, psiModTracker,
        java.util.List.of(new ScalaPsiChangeListener(project)))
      psiManagerRef = psiManager
      projectServices.register(classOf[PsiManager])(psiManager)
      projectServices.register(classOf[PsiModificationTracker])(psiModTracker)
      // Host root changes already invalidate PSI; using that count also invalidates Java module-descriptor caches.
      projectServices.register(classOf[ProjectRootModificationTracker])(
        new ProjectRootModificationTracker {
          override def getModificationCount: Long = psiModTracker.getModificationCount
        })
      projectServices.register(classOf[PsiJavaModuleModificationTracker]) {
        val tracker = new PsiJavaModuleModificationTracker(project)
        Disposer.register(root, tracker)
        tracker
      }
      // PSI <-> document synchronization without the IDE's global editor event multicaster.
      projectServices.register(classOf[PsiDocumentManager]) {
        val documents = new HostPsiDocumentManager(project)
        // Commits are explicit and synchronous (ScalaPsiHost.edit), never the IDE's background commit after typing.
        documents.disableBackgroundCommit(root)
        documents
      }
      // Platform library implementations, constructed by the host (no container wiring).
      // Local unused-symbol analysis asks the profile only for the native entry-point policy.
      // Keep that policy at platform defaults; this host does not import or persist desktop profiles.
      val entryPoints = new EntryPointsManagerBase(project) {
        /** Unsupported: recorded and rejected by `CallLog`. */
        override def configureAnnotations(): Unit = CallLog.unsupported("EntryPointsManager.configureAnnotations")
      }
      Disposer.register(root, entryPoints)
      projectServices.register(classOf[EntryPointsManager])(entryPoints)
      projectServices.register(classOf[InspectionProjectProfileManager]) {
        val profiles = new HostInspectionProfileManager(projectBus)
        Disposer.register(root, profiles)
        profiles
      }
      // The plugin's unused-declaration inspection owns the local reference-search pipeline and its PSI caches.
      locally {
        // A class loaded by name is a reference type.
        val cls = Class.forName(
          "org.jetbrains.plugins.scala.codeInspection.declarationRedundancy.cheapRefSearch.SearchMethodsWithProjectBoundCache")
          .asInstanceOf[Class[AnyRef]]
        projectServices.register(cls)(newPrivate(cls, project))
      }
      projectServices.register(classOf[CachedValuesManager])(
        new CachedValuesManagerImpl(project, new PsiCachedValuesFactory(project)))
      projectServices.register(classOf[PsiFileFactory])(new PsiFileFactoryImpl(psiManager))
      projectServices.register(classOf[ProjectRootManager])(new Host_ProjectRootManager(dispatch {
        case ("ProjectRootManager.getFileIndex", _) => fileIndex
        // JDK roots are mounted directly; the host has no configured IntelliJ SDK entity.
        case ("ProjectRootManager.getProjectSdk", _) => null
        // Each host module carries its root manager (see ScalaPsiHost.newModule).
        case ("ProjectRootManager.getModuleRootManager", Array(m: Module)) =>
          m.getService(classOf[ModuleRootManager])
      }))
      // The host's indexes are always ready before it answers queries.
      projectServices.register(classOf[DumbService])(new Host_DumbService(dispatch {
        case ("DumbService.isDumb", _) => java.lang.Boolean.FALSE
        // The host never enters dumb mode, so this state has no transitions to invalidate.
        case ("DumbService.getModificationTracker", _) => ModificationTracker.NEVER_CHANGED
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
      projectServices.register(classOf[IncompleteDependenciesService])(
        tableProxy(classOf[IncompleteDependenciesService], "IncompleteDependenciesService") {
          case ("getState" | "getStateUnsafe", _) => IncompleteDependenciesService.DependenciesState.COMPLETE
        })
      // Every file gets full highlighting ("All Problems"); the IDE's per-file level is a UI setting.
      projectServices.register(classOf[HighlightingLevelManager])(new Host_HighlightingLevelManager(dispatch {
        case ("HighlightingLevelManager.shouldHighlight" | "HighlightingLevelManager.shouldInspect", _) =>
          java.lang.Boolean.TRUE
        case ("HighlightingLevelManager.runEssentialHighlightingOnly", _) => java.lang.Boolean.FALSE
      }))
      projectServices.register(classOf[ModuleManager])(new Host_ModuleManager(dispatch {
        case ("ModuleManager.getModules", _) => moduleModel.all.map(_.module).toArray
        case ("ModuleManager.findModuleByName", Array(name: String)) => moduleModel.byId(name).map(_.module).orNull
      }))
      // Compiler settings per module, configured from the build's scalac options as the plugin's importers do.
      projectServices.register(classOf[ScalaCompilerConfiguration])(new ScalaCompilerConfiguration(project))
      // The compiler plugins the plugin supports, where a module's scalac options enable them: kind-projector's syntax
      // (`λ`, `*`, `?`) and better-monadic-for's desugaring of for-comprehensions.
      projectServices.register(classOf[KindProjectorUtil])(new KindProjectorUtil(project))
      projectServices.register(classOf[BetterMonadicForSupport])(new BetterMonadicForSupport(project))
      // PSI write path: the plugin edits non-physical copies (e.g. for-comprehension desugaring).
      projectServices.register(classOf[PomModel])(
        classOf[PomModel].cast(newPrivate(
          Class.forName("com.intellij.psi.impl.source.PostprocessReformattingAspectImpl$LangPomModel"), project)))
      projectServices.register(classOf[TreeAspect])(new TreeAspect)
      projectServices.register(classOf[SmartPointerManager])(new SmartPointerManagerImpl(project))
      projectServices.register(classOf[PsiElementFactory])(new PsiElementFactoryImpl(project))
      projectServices.register(classOf[PsiNameHelper])(PsiNameHelperImpl.getInstance())
      // One scope for everything, as one scope per module: the plugin keys its caches by scope, so a new object per
      // request would add entries on every lookup that are never found again.
      val everythingScope = new EverythingGlobalScope(project)
      // Packages are declared in the files' text, not by directories (the host's files have none): the index holds no
      // directory, and lists the files that declare a package, which the package scope of a package-private member
      // needs.
      projectServices.register(classOf[PackageIndex])(new PackageIndex {
        override def getDirsByPackageName(packageName: String, includeLibrarySources: Boolean): Query[VirtualFile] =
          EmptyQuery.getEmptyQuery
        override def getFilesByPackageName(packageName: String): Query[VirtualFile] = {
          val declaring = moduleModel.all.flatMap(_.sources.asScala).filter { source =>
            PsiManager.getInstance(project).findFile(source) match {
              case owner: PsiClassOwner => owner.getPackageName == packageName
              case _ => false
            }
          }
          new CollectionQuery(declaring.asJava)
        }
        override def getPackageNameByDirectory(dir: VirtualFile): String = null
      })
      projectServices.register(classOf[JavaResolveCache])(new JavaResolveCache(project))
      // The project's Java language level, which a class's declarations are processed at (the highest, as the files'
      // own).
      projectServices.register(classOf[LanguageLevelProjectExtension])(new LanguageLevelProjectExtension {
        override def getLanguageLevel: LanguageLevel = LanguageLevel.HIGHEST
        /** Unsupported: recorded and rejected by `CallLog`. */
        override def setLanguageLevel(level: LanguageLevel): Unit =
          CallLog.unsupported("LanguageLevelProjectExtension.setLanguageLevel")
        override def getDefault: java.lang.Boolean = java.lang.Boolean.TRUE
        /** Unsupported: recorded and rejected by `CallLog`. */
        override def setDefault(value: java.lang.Boolean): Unit =
          CallLog.unsupported("LanguageLevelProjectExtension.setDefault")
      })
      projectServices.register(classOf[ProjectScopeBuilder])(new Host_ProjectScopeBuilder(dispatch {
        case ("ProjectScopeBuilder.buildProjectScope" | "ProjectScopeBuilder.buildContentScope", _) =>
          moduleModel.sourcesScope
        case ("ProjectScopeBuilder.buildLibrariesScope" | "ProjectScopeBuilder.buildLibraryClassesScope", _) =>
          moduleModel.librariesScope
        case ("ProjectScopeBuilder.buildAllScope" | "ProjectScopeBuilder.buildEverythingScope", _) => everythingScope
      }))
      // Class and package lookup: the platform's JavaPsiFacadeImpl over element finders, as in the IDE. The plugin's
      // finders come with the host's LibraryClassFinder, which takes the place of the IDE's PsiElementFinderImpl.
      projectServices.register(classOf[JvmPsiConversionHelper])(new JvmPsiConversionHelperImpl)
      projectServices.register(classOf[ResolveCache])(new ResolveCache(project))
      projectServices.register(classOf[PsiMigrationManager])(new PsiMigrationManager(project))
      // External (XML) annotations of library classes: the host attaches none.
      projectServices.register(classOf[ExternalAnnotationsManager])(new Host_ExternalAnnotationsManager(dispatch {
        case ("ExternalAnnotationsManager.findExternalAnnotationsFiles", _) => null
        case ("ExternalAnnotationsManager.findExternalAnnotations", Array(_)) => PsiAnnotation.EMPTY_ARRAY
        case ("ExternalAnnotationsManager.findExternalAnnotations", Array(_, _)) => java.util.List.of()
      }))
      projectServices.register(classOf[InferredAnnotationsManager])(new HostInferredAnnotationsManager)
      // Nullability annotations of library Java types: the host configures none (the IDE's defaults come from
      // settings).
      projectServices.register(classOf[NullableNotNullManager])(new Host_NullableNotNullManager(project, dispatch {
        case ("NullableNotNullManager.getNullityDefault", _) => ContextNullabilityInfo.EMPTY
        case ("NullableNotNullManager.findEffectiveNullabilityInfo", _) => null
        // Asked of a type's annotations while substituting generics (the plugin's isInheritor on a class file).
        case ("NullableNotNullManager.getAnnotationNullability", _) => java.util.Optional.empty()
      }))
      // Java class files navigate to their sources through this helper, as the IDE's: the source file of the same
      // package and name in the sources attached to the class's library, or the class file itself (a library without
      // sources).
      projectServices.register(classOf[JavaPsiImplementationHelper])(new Host_JavaPsiImplementationHelper(dispatch {
        case ("JavaPsiImplementationHelper.getClsFileNavigationElement", Array(f: PsiJavaFile)) =>
          val source = for {
            classFile <- Option(f.getVirtualFile).collect { case lib: LibraryFile => lib }
            sourceRoot <- Option(librarySources.get(classFile.root))
            sourceName <- f.getClasses.headOption.collect { case c: ClsClassImpl => c.getSourceFileName }
            directory = classFile.entry.lastIndexOf('/') match {
              case -1 => ""
              case i => classFile.entry.substring(0, i + 1)
            }
            sourceFile <- Option(sourceRoot.findFileByRelativePath(directory + sourceName))
            // As in the IDE, only a class owner: a source the host cannot parse (Kotlin) opens as a binary file.
            psi <- Option(psiManagerRef.findFile(sourceFile)).collect { case owner: PsiClassOwner => owner }
          } yield psi
          source.getOrElse(f)
        // A class of a file that is not a copy is its own original.
        case ("JavaPsiImplementationHelper.getOriginalClass", Array(c: PsiClass))
            if c.getContainingFile.getOriginalFile eq c.getContainingFile => c
        // Java sources parse at the highest language level: it accepts the syntax of every Java release the build may
        // target (the build's own release is not read yet).
        case ("JavaPsiImplementationHelper.getEffectiveLanguageLevel", _) => LanguageLevel.HIGHEST
        case ("JavaPsiImplementationHelper.getDefaultImportAnchor",
              Array(list: PsiImportList, statement: PsiImportStatementBase)) =>
          new ImportHelper(
            JavaCodeStyleSettings.getInstance(statement.getContainingFile)).getDefaultAnchor(list, statement)
      }))
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.importFilter", classOf[ImportFilter])
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.codeStyle.ReferenceAdjuster",
        classOf[LanguageExtensionPoint[?]])
      ReferenceAdjuster.Extension.INSTANCE.addExplicitExtension(JavaLanguage.INSTANCE, new JavaReferenceAdjuster)
      // Java completion renders static-import preferences and inserts source imports through the platform manager.
      projectServices.register(classOf[JavaCodeStyleManager])(new JavaCodeStyleManagerImpl(project))
      // Source classes combine Scala synthetic representations with Java stub entries; Java binaries remain on demand.
      lazy val scalaShortNames = new ScalaShortNamesCache(using project)
      projectServices.register(classOf[PsiShortNamesCache])(new Host_PsiShortNamesCache(dispatch {
        case ("PsiShortNamesCache.getClassesByName", Array(name: String, scope: GlobalSearchScope)) =>
          val binaries = libraryFiles.filesNamed(name + ".class")
            .collect {
              case binary: LibraryFile if !binary.isLibrarySource && !binary.isDirectory && scope.contains(binary) =>
                binary
            }
            .flatMap(binary => Option(psiManagerRef.findFile(binary)))
            .collect { case owner: PsiClassOwner => owner.getClasses.toSeq }
            .flatten
            .filter(_.getName == name)
          (scalaShortNames.getClassesByName(name, scope).toSeq ++
            StubIndex.getElements(JavaStubIndexKeys.CLASS_SHORT_NAMES,
              name, project, scope, classOf[PsiClass]).asScala ++ binaries).distinct.toArray
        // Inherited main/suite discovery passes the finite set of superclass files, including Java binaries.
        case ("PsiShortNamesCache.getMethodsByName", Array(name: String, scope: GlobalSearchScope)) =>
          /** Lists the methods named `name` declared by `owner` and its inner classes. */
          def methods(owner: PsiClass): Seq[PsiMethod] =
            owner.getMethods.toSeq.filter(_.getName == name) ++ owner.getInnerClasses.toSeq.flatMap(methods)

          val files = if (scope == GlobalSearchScope.EMPTY_SCOPE) Seq.empty
          else {
            val enumeration = VirtualFileEnumeration.extract(scope)
            Option(enumeration).flatMap(value => Option(value.getFilesIfCollection)).map(_.asScala.toSeq)
              .getOrElse(CallLog.unsupported("PsiShortNamesCache.getMethodsByName requires a finite file scope"))
          }

          files.flatMap { source =>
            ProgressManager.checkCanceled()
            Option(psiManagerRef.findFile(source)).toSeq.collect {
              case file: PsiJavaFile => file.getClasses.toSeq.flatMap(methods)
            }.flatten
          }.distinct.toArray
        case ("PsiShortNamesCache.getAllClassNames", _) =>
          (scalaShortNames.getAllClassNames.toSeq ++
            StubIndex.getInstance.getAllKeys(JavaStubIndexKeys.CLASS_SHORT_NAMES, project).asScala ++
            libraryFiles.classFileNames).distinct.sorted.toArray
      }))
      // Annotation lookups cache by owner; the host has no mirrored elements (light copies of sources), so an owner is
      // its own cache key.
      projectServices.register(classOf[AnnotationCacheOwnerNormalizer])(
        new Host_AnnotationCacheOwnerNormalizer(dispatch {
          case ("AnnotationCacheOwnerNormalizer.doNormalize", Array(owner)) => owner
        }))
      // Auto-import exclusions, read by quick fixes that look for importable implicit instances: the defaults.
      projectServices.register(classOf[JavaProjectCodeInsightSettings])(new JavaProjectCodeInsightSettings)
      // Code-insight contexts (multiverse: one file seen from several modules): off, so every file is in the
      // default context, as in the IDE without multiverse.
      projectServices.register(classOf[CodeInsightContextManager])(
        tableProxy(classOf[CodeInsightContextManager], "CodeInsightContextManager") {
          case ("getCodeInsightContext" | "getCodeInsightContextRaw" | "getPreferredContext", _) =>
            CodeInsightContexts.defaultContext()
          case ("getCodeInsightContexts", _) => java.util.List.of(CodeInsightContexts.defaultContext())
        })
      // Java code style of decompiled class files (indents): the platform's core defaults, as headless compilers use.
      appServices.register(classOf[JavaFileCodeStyleFacadeFactory])(
        tableProxy(classOf[JavaFileCodeStyleFacadeFactory], "JavaFileCodeStyleFacadeFactory") {
          case ("createFacade", _) => new CoreJavaFileCodeStyleFacade
        })
      // The IDE decompiles a Java class file (its text, for the class's PSI mirror) in its default project, as a
      // throwaway class-file PSI; the host's one project takes that role.
      appServices.register(classOf[DefaultProjectFactory])(new Host_DefaultProjectFactory(dispatch {
        case ("DefaultProjectFactory.getDefaultProject", _) => project
      }))
      projectServices.register(classOf[CodeStyleManager])(new CodeStyleManagerImpl(project))
      projectServices.register(classOf[TemplateManager])(new HostTemplateManager)
      projectServices.register(classOf[ReadonlyStatusHandler])(new HostReadonlyStatusHandler)
      // No language injection in the host: every file is its own top-level file.
      projectServices.register(classOf[InjectedLanguageManager])(new Host_InjectedLanguageManager(dispatch {
        case ("InjectedLanguageManager.getTopLevelFile", Array(e: PsiElement)) => e.getContainingFile
        case ("InjectedLanguageManager.findInjectedElementAt" | "InjectedLanguageManager.getInjectedPsiFiles", _) =>
          null
        case ("InjectedLanguageManager.isInjectedViewProvider", _) => java.lang.Boolean.FALSE
        case ("InjectedLanguageManager.getCachedInjectedDocumentsInRange", _) => java.util.List.of()
        // There are no registered injectors, so enumeration has no injected fragments to visit.
        case ("InjectedLanguageManager.enumerate", _) => null
        // Without injected files every element is in its host file, so its ranges are already host ranges.
        case ("InjectedLanguageManager.injectedToHost", Array(_: PsiElement, range: TextRange)) => range
      }))
      // JVM-language-neutral class lookup that JavaPsiFacadeImpl also consults; no other JVM language provides classes.
      CoreApplicationEnvironment.registerExtensionPoint(area, JvmElementProvider.EP_NAME.getName,
        classOf[JvmElementProvider])
      projectServices.register(classOf[JvmFacade])(new JvmFacadeImpl(project))
      projectServices.register(classOf[JavaFileManager])(
        tableProxy(classOf[JavaFileManager], "JavaFileManager") {
          // The build's source roots have no package prefix.
          case ("getNonTrivialPackagePrefixes", _) => java.util.List.of[String]()
        })
      projectServices.register(classOf[JavaPsiFacade])(new JavaPsiFacadeImpl(project, null))
      // Resolution inside Java sources (mixed projects).
      projectServices.register(classOf[PsiResolveHelper])(new PsiResolveHelperImpl(project))
      // ScalaDocParserDefinition reads TAB_SIZE from code style: one fixed settings object, never the scheme manager.
      projectServices.register(classOf[ProjectCodeStyleSettingsManager]) {
        val settings = new ProjectCodeStyleSettingsManager(project, /* loadExtensions = */ false)
        settings.USE_PER_PROJECT_SETTINGS = true
        settings.setMainProjectCodeStyle(new CodeStyleSettings)
        settings
      }
      projectServices.register(classOf[ResolveScopeManager])(new Host_ResolveScopeManager(dispatch {
        // Sources resolve in their module, library classes in the modules that have the library, anything else
        // everywhere; sources are used in their module and the modules that depend on it.
        // Stub types and other synthetic files carry their declaration's context, as ResolveScopeManagerImpl follows.
        case ("ResolveScopeManager.getResolveScope", Array(e: PsiElement)) =>
          contextFile(e).flatMap(moduleModel.resolveScope).getOrElse(everythingScope)
        case ("ResolveScopeManager.getUseScope", Array(e: PsiElement)) =>
          contextFile(e).flatMap(moduleModel.useScope).getOrElse(everythingScope)
        case ("ResolveScopeManager.getDefaultResolveScope", Array(f: VirtualFile)) =>
          moduleModel.resolveScope(f).getOrElse(everythingScope)
      }))
      // Compiler indices (searching bytecode for implicit, apply and SAM usages) need a project task runner, JPS or sbt
      // shell, which the host lacks: the settings find none, are inactive, and the plugin searches the text only.
      declare("com.intellij.projectTaskRunner")
      projectServices.register(classOf[ProjectTaskManager])(
        new Host_ProjectTaskManager(project, dispatch(PartialFunction.empty)))
      projectServices.register(classOf[CompilerIndicesSettings])(new CompilerIndicesSettings(project))
      locally {
        // A class loaded by name is a reference type.
        val cachesClass = Class.forName("com.intellij.psi.impl.search.HighlightingCaches").asInstanceOf[Class[AnyRef]]
        projectServices.register(cachesClass)(newPrivate(cachesClass, project))
      }
      projectServices.register(classOf[PsiSearchHelper])(new PsiSearchHelperImpl(project))
      // The options of the plugin's find-usages handler: its own defaults.
      projectServices.register(classOf[ScalaFindUsagesConfiguration])(new ScalaFindUsagesConfiguration(project))
      // Index-backed Scala symbol lookup: the real class, answered by the host's StubIndex.
      projectServices.register(classOf[ScalaShortNamesCacheManager])(new ScalaShortNamesCacheManager(using project))
      // LibraryExtensionsManager is built by its own constructor. With library extensions disabled it loads nothing,
      // so dynamic extension lookups (SyntheticMembersInjector, ScalaMacroEvaluator) answer "none". It keeps a
      // PropertiesComponent that it reads only when extensions are enabled.
      projectServices.register(classOf[PropertiesComponent])(
        new Host_PropertiesComponent(dispatch(PartialFunction.empty)))
      projectServices.register(classOf[LibraryExtensionsManager])(new LibraryExtensionsManager(project))
      // Settings are plain state beans with defaults (later: from LSP configuration).
      projectServices.register(classOf[ScalaProjectSettings]) {
        val settings = new ScalaProjectSettings(project)
        // Library extensions are jars downloaded from build resolvers and loaded into the IDE: out of scope.
        settings.setEnableLibraryExtensions(false)
        // Where errors come from: the build's compiler (the caller adds its problems) or the plugin's type-aware
        // checks.
        settings.setCompilerHighlightingScala3(config.compilerErrorsScala3)
        settings.setCompilerHighlightingScala2(config.compilerErrorsScala2)
        // Type mismatches as diagnostics on the expression, not as editor inlay hints (which LSP clients lack).
        settings.setTypeMismatchHints(false)
        settings
      }
      projectServices.register(classOf[ScalaPsiManager])(new ScalaPsiManager(using project))
      projectServices.register(classOf[StdTypes])(new StdTypes(using new ProjectContext(project)))
      projectServices.register(classOf[SyntheticClasses])(new SyntheticClasses(project))
      // Scala 3 type operations (union joins).
      projectServices.register(classOf[Scala3Bounds])(Scala3Bounds(project))
      projectServices.register(classOf[ScalaMacroEvaluator])(new ScalaMacroEvaluator(project))
      // Private light services (@Service): the container builds them by class name, and so does the host.
      Seq(
        "org.jetbrains.plugins.scala.lang.psi.types.api.ParameterizedType$SubstitutorCacheService",
        "org.jetbrains.plugins.scala.util.UnloadAwareDisposable$UnloadAwareDisposableService",
        "org.jetbrains.plugins.scala.lang.psi.types.ScMatchType$MatchTypeReductionCacheService",
      ).foreach { name =>
        // A class loaded by name is a reference type.
        val cls = Class.forName(name).asInstanceOf[Class[AnyRef]]
        projectServices.register(cls)(newPrivate(cls))
      }

      // Element finders are built last: their constructors fetch project services.
      CoreApplicationEnvironment.registerExtensionPoint(area, "com.intellij.java.elementFinder",
        classOf[PsiElementFinder])
      // `area` is an ExtensionsAreaImpl, which registers ExtensionPointImpl instances.
      val finders = area.getExtensionPoint[PsiElementFinder]("com.intellij.java.elementFinder")
        .asInstanceOf[ExtensionPointImpl[PsiElementFinder]]
      finders.registerExtension(new LibraryClassFinder(project, libraryFiles, moduleModel), root)
      finders.registerExtension(new ScalaClassFinder(project), root)
      finders.registerExtension(new SyntheticClassElementFinder(project), root)
    }

    /** Invalidates source/library PSI caches and the platform's project modification counter. */
    private def psiChanged(): Unit = HostLock.write {
      ScalaPsiHost.notifyPsiChanged(project)
      psiModTracker.incCounter()
      ScalaPsiManager.instance(project).clearOnNonScalaChange()
    }

    /** Runs the plugin's project startup activity after all services and element finders are registered. */
    private def startProject(): ScalaPsiHost = {
      // ---- 4. Project startup the PSI depends on -------------------------------------------------
      // The plugin's own activity registers the synthetic classes (Any, Int, ...) through DumbService.queueTask.
      new RegisterSyntheticClassesProjectActivity().execute(project)

      new ScalaPsiHost(config.stateDir, project, symbolIndex, wordIndex, importIndex, libraryFiles, librarySources,
        moduleModel, () => psiChanged(),
        new CodeActions(
          Descriptors.scalaExtensions(config.scalaPluginJars, "com.intellij.intentionAction") ++ PlatformListIntentions,
          Descriptors.scalaExtensions(config.scalaPluginJars, "com.intellij.lang.surroundDescriptor"),
          Descriptors.scalaExtensions(config.scalaPluginJars, "com.intellij.lang.unwrapDescriptor")),
        new Inspections(Descriptors.scalaExtensionBeans(config.scalaPluginJars, "com.intellij.localInspection")))
    }
  }

  /** Invokes the declared constructor of `cls` that takes `args.size` parameters, ignoring its visibility. */
  private def newPrivate[T](cls: Class[T], args: AnyRef*): T = {
    val constructor = cls.getDeclaredConstructors.find(_.getParameterCount == args.size)
      .getOrElse(throw new IllegalStateException(s"no ${args.size}-argument constructor in ${cls.getName}"))
    constructor.setAccessible(true)
    cls.cast(constructor.newInstance(args*))
  }
}
