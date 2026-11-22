package scalaanalyzer.language

import com.intellij.codeInsight.generation.{
  ClassMember as ChooserMember, GenerateMembersUtil, OverrideImplementExploreUtil, OverrideImplementUtil,
  PsiMethodMember,
}
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.editor.Editor
import com.intellij.pom.java.JavaFeature
import com.intellij.psi.{PsiClass, PsiCompiledElement, PsiFile, PsiJavaFile, PsiMethod, PsiModifier}
import com.intellij.psi.codeStyle.JavaCodeStyleSettings
import com.intellij.psi.infos.CandidateInfo
import com.intellij.psi.util.PsiUtil
import org.jetbrains.plugins.scala.editor.ScalaEditorUtils
import org.jetbrains.plugins.scala.lang.psi.api.ScalaFile
import org.jetbrains.plugins.scala.lang.psi.api.statements.ScExtension
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTemplateDefinition
import org.jetbrains.plugins.scala.overrideImplement.{
  ClassMember, ClassMember0, ScExtensionMember, ScExtensionMethodMember, ScalaGenerationInfo, ScalaMember,
  ScalaOIUtil, ScalaOverridableMember,
}

import scalaanalyzer.platform.{HostLock, RequestEditor}

import java.lang.reflect.InvocationTargetException

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * Implements and overrides members as the plugin's Implement Members (Ctrl+I) and Override Members (Ctrl+O) actions
 * do, with the client choosing the members where the IDE shows its chooser dialog.
 *
 * The plugin decides which members a class offers (`ScalaOIUtil`), in what order they are listed and where, how and
 * with what bodies they are inserted. The class is the one around the caret, as for the actions.
 *
 * A Java class has the same through the Java plugin's own code (`OverrideImplementUtil` and
 * `OverrideImplementExploreUtil`), which the actions of its dialogs run.
 */
object MemberOverrides {
  /**
   * A member the chooser lists for a class.
   *
   * @param id opaque key of the member, which identifies it again while the class and its supertypes are unchanged;
   *           no other member listed for the class has it
   * @param signature the member as the chooser shows it, such as `foo(x: Int): String`
   * @param owner the class or trait that declares the member
   */
  final case class Member(id: String, signature: String, owner: String)

  /**
   * An override named members that the class does not offer, as when the text changed after they were listed.
   *
   * @param ids the identifiers that no offered member has
   */
  final class UnknownMembers(val ids: Seq[String])
      extends RuntimeException(s"no such members to override: ${ids.mkString(", ")}")

  /** The class at the caret and the members the chooser lists for it, in the chooser's order. */
  private final case class Listing(clazz: ScTemplateDefinition, members: Seq[ClassMember])

  /**
   * The Java class at the caret and what the chooser of the Java plugin shows for it.
   *
   * @param clazz the class
   * @param members the members in the chooser's order
   * @param preselected the members that Implement Methods selects when the chooser opens: those that the class has to
   *                    implement, in the chooser's order; none for Override Methods, which selects nothing
   * @param insertOverride whether the chooser's `@Override` option is on, which is its default
   */
  private final case class JavaListing(
      clazz: PsiClass, members: Seq[PsiMethodMember], preselected: Seq[PsiMethodMember], insertOverride: Boolean)

  /**
   * The plugin's `TypeAdjuster`, which Scala keeps private to the plugin's package: it shortens the types of the
   * inserted members and imports what they need.
   */
  private val TypeAdjusterClass = Class.forName("org.jetbrains.plugins.scala.lang.psi.TypeAdjuster")

  /** The plugin's way to turn a member into the text that is inserted; its third parameter is a `TypeAdjuster`. */
  private val NewGenerationInfo =
    classOf[ScalaGenerationInfo].getConstructor(classOf[ScalaMember], classOf[Boolean], TypeAdjusterClass)

  /**
   * Whether Implement Members has something to implement for the class at the offset.
   *
   * The caller holds the host read lock; the file is unchanged.
   *
   * @param file Scala or Java source being queried
   * @param offset UTF-16 caret offset
   * @return `true` when the caret is in a class, object or trait that has abstract members left to implement; for a
   *         Java class, abstract methods only: a default method of an interface is offered by the dialog but not
   *         required
   */
  def hasMembersToImplement(file: ScalaFile | PsiJavaFile, offset: Int): Boolean = atCaret(file, offset) { editor =>
    file match {
      case scalaFile: ScalaFile => listing(scalaFile, editor, implement = true).exists(_.members.nonEmpty)
      case javaFile: PsiJavaFile => javaListing(javaFile, editor, implement = true).exists(_.preselected.nonEmpty)
    }
  }

  /**
   * Previews the insertion of every member the class at the offset has to implement, the chooser's default selection.
   *
   * Takes read access itself and leaves the file unchanged. A Scala file's preview runs on the host's dispatch thread
   * (see [[inserting]]), so the caller must not be reading or editing; a Java file's runs on the calling thread.
   *
   * For a Java class the default selection of Implement Methods is the abstract methods, which is what the Java
   * plugin's "Implement methods" fix inserts; a class with only default methods left to implement gets nothing, where
   * the dialog would preselect an arbitrary one of them.
   *
   * @param file Scala or Java source to edit
   * @param offset UTF-16 caret offset; nothing is inserted when it is not in a class, object or trait
   * @return the changed text and the edits against the current text of `file`
   */
  def implementAll(file: ScalaFile | PsiJavaFile, offset: Int): ChangeEngine.Result = file match {
    case scalaFile: ScalaFile =>
      inserting {
        ChangeEngine.run(scalaFile) { copy =>
          val editor = previewEditorAt(offset)

          listing(copy, editor, implement = true).foreach(found => insert(found.members, found.clazz, editor))
        }._1
      }
    case javaFile: PsiJavaFile =>
      previewJava(javaFile) { copy =>
        val editor = previewEditorAt(offset)

        javaListing(copy, editor, implement = true).foreach(found => insertJava(found.preselected, found, editor))
      }._1
  }

  /**
   * Lists the members Override Members offers for the class at the offset.
   *
   * The caller holds the host read lock; the file is unchanged.
   *
   * @param file Scala or Java source being queried
   * @param offset UTF-16 caret offset
   * @return the chooser's members in its order, grouped by owner as the chooser does; empty when the caret is not in
   *         a class, object or trait, or nothing is left to override. A Java class lists the methods it can override
   *         and, as the dialog does by default, the abstract ones it has to implement
   */
  def overridable(file: ScalaFile | PsiJavaFile, offset: Int): Seq[Member] = atCaret(file, offset) { editor =>
    file match {
      case scalaFile: ScalaFile =>
        listing(scalaFile, editor, implement = false).toSeq.flatMap(found => rows(found.members))
      case javaFile: PsiJavaFile =>
        javaListing(javaFile, editor, implement = false).toSeq.flatMap(found => rows(found.members))
    }
  }

  /**
   * Computes the edits that override the chosen members in the class at the offset.
   *
   * Takes read access itself and leaves the file unchanged. A Scala file's preview runs on the host's dispatch thread
   * (see [[inserting]]), so the caller must not be reading or editing; a Java file's runs on the calling thread.
   *
   * @param file Scala or Java source to edit
   * @param offset UTF-16 caret offset, where the members are inserted
   * @param ids identifiers of the members to override, as [[overridable]] gave them
   * @return the edits against the current text of `file`
   * @throws UnknownMembers when an identifier is not that of a member the class offers
   */
  def overrideMembers(file: ScalaFile | PsiJavaFile, offset: Int, ids: Seq[String]): Seq[ChangeEngine.Edit] =
    file match {
      case scalaFile: ScalaFile =>
        inserting {
          ChangeEngine.run(scalaFile) { copy =>
            val editor = previewEditorAt(offset)
            val offered = listing(copy, editor, implement = false)
            val byId = offered.toSeq.flatMap(found => rows(found.members).map(_.id).zip(found.members)).toMap
            val unknown = ids.filterNot(byId.contains)

            if (unknown.nonEmpty) throw new UnknownMembers(unknown)
            offered.foreach(found => insert(ids.distinct.map(byId), found.clazz, editor))
          }._1.edits
        }
      case javaFile: PsiJavaFile =>
        previewJava(javaFile) { copy =>
          val editor = previewEditorAt(offset)
          val offered = javaListing(copy, editor, implement = false)
          val listed = offered.toSeq.flatMap(found => rows(found.members).map(_.id).zip(found.members))
          val unknown = ids.filterNot(listed.map(_._1).toSet)

          if (unknown.nonEmpty) throw new UnknownMembers(unknown)
          // The dialog gives its selection in the order it lists it, and the members are inserted in that order.
          val chosen = listed.collect { case (id, member) if ids.contains(id) => member }

          offered.foreach(found => insertJava(chosen, found, editor))
        }._1.edits
    }

  /**
   * Runs a preview that inserts members on the host's dispatch thread: the plugin's type adjuster, which the insertion
   * uses, insists on the event thread. The preview's session, its copy and its postponed formatting all belong to the
   * thread that runs it, so the whole preview goes there, as a query of its own (see
   * [[scalaanalyzer.platform.HostLock.queryOnDispatchThread]]).
   */
  private def inserting[A](preview: => A): A = HostLock.queryOnDispatchThread(preview)

  /**
   * Runs a preview on the copy of a Java file. The Java plugin's insertion needs no dispatch thread: it is what the
   * "Implement methods" fix of its errors previews, which runs under the caller's read access with the other previews
   * of Java fixes.
   */
  private def previewJava[A](file: PsiJavaFile)(modify: PsiJavaFile => A): (ChangeEngine.Result, A) =
    ChangeEngine.run[A](file) {
      case copy: PsiJavaFile => modify(copy)
      case other => throw new IllegalStateException(s"change copy is not a Java file: ${other.getClass.getName}")
    }

  /**
   * The class at the editor's caret, chosen as the plugin's actions do, with the members it offers to implement or
   * override.
   */
  private def listing(file: PsiFile, editor: Editor, implement: Boolean): Option[Listing] =
    ScalaOIUtil.getTemplateDefinitionForOverrideImplementAction(file, editor).map { clazz =>
      val members =
        if (implement) ScalaOIUtil.getMembersToImplement(clazz, withSelfType = true)
        else ScalaOIUtil.getMembersToOverride(clazz)

      Listing(clazz, inChooserOrder(members, clazz))
    }

  /**
   * Orders members as the chooser lists them: by owner, the class's direct supertypes first and then the other owners
   * with the subtypes before their supertypes (by name when unrelated), and by position in the source within an owner.
   *
   * This is the rule of the plugin's `ScalaMemberChooser.sorted`, which the host cannot call: loading the chooser's
   * companion needs the dialog's UI classes, which the host does not have.
   *
   * @return one group per owner, in order
   */
  private def ownerGroups(members: Seq[ClassMember], clazz: ScTemplateDefinition): Seq[Seq[ClassMember]] = {
    val byOwner = members.groupBy(_.getElement.getContainingClass)
    val subtypesFirst = new Ordering[PsiClass] {
      override def compare(left: PsiClass, right: PsiClass): Int = {
        val leftInherits = left.isInheritor(right, true)
        val rightInherits = right.isInheritor(left, true)

        // Generic traits of Scala can inherit each other cyclically.
        if (leftInherits && rightInherits) 0
        else if (leftInherits) -1
        else if (rightInherits) 1
        else Option(left.getName).getOrElse("").compareTo(Option(right.getName).getOrElse(""))
      }
    }
    val owners = (clazz.supers ++ byOwner.keys.toSeq.sorted(using subtypesFirst)).distinct

    owners.flatMap(byOwner.get).map(_.sortBy(_.getElement.getTextOffset))
  }

  /**
   * The Java class around the caret with the members the Java plugin's chooser lists for it, found as the plugin's
   * Override Methods and Implement Methods actions do.
   *
   * The class is `OverrideImplementUtil.getContextClass`, which only Implement Methods lets be an interface where the
   * language level has no default methods (`JavaImplementMethodsHandler`). The candidates are those of
   * `OverrideImplementUtil.prepareChooser` and `prepareJavaOverrideImplementChooser`: the dialog lists the methods to
   * override and then, since its default is to show the two together, those to implement; Implement Methods lists the
   * methods to implement, the default methods of interfaces unselected. The order is the dialog's (see
   * [[javaChooserOrder]]), and `@Override` is on as the dialog has it: its setting, offered where the language level
   * allows (`JavaOverrideImplementMemberChooser.prepare`, `isInsertOverrideAnnotationSelected`). Javadoc is not copied,
   * which is the checkbox's default (`MemberChooser.PROP_COPYJAVADOC` is unset).
   */
  private def javaListing(file: PsiJavaFile, editor: Editor, implement: Boolean): Option[JavaListing] = {
    val allowInterface = !implement || PsiUtil.isAvailable(JavaFeature.EXTENSION_METHODS, file)

    Option(OverrideImplementUtil.getContextClass(file.getProject, editor, file, allowInterface)).map { clazz =>
      val candidates = OverrideImplementExploreUtil.getMethodsToOverrideImplement(clazz, implement).asScala.toSeq
      val isDefault = (candidate: CandidateInfo) =>
        candidate.getElement match {
          case method: PsiMethod => method.hasModifierProperty(PsiModifier.DEFAULT)
          case _ => false
        }
      val (primary, secondary) =
        if (implement) candidates.partition(!isDefault(_))
        else
          (candidates,
            if (clazz.isInterface) Seq.empty
            else OverrideImplementExploreUtil.getMethodsToOverrideImplement(clazz, true).asScala.toSeq)
      val shown = javaChooserOrder((primary ++ secondary).distinct.map(new PsiMethodMember(_)))
      val level = PsiUtil.getLanguageLevel(clazz)
      val overrideAvailable =
        JavaFeature.OVERRIDE_INTERFACE.isSufficient(level) || JavaFeature.ANNOTATIONS.isSufficient(level) && !implement

      JavaListing(clazz, shown, if (implement) preselected(shown, primary, clazz) else Seq.empty,
        overrideAvailable && JavaCodeStyleSettings.getInstance(file).INSERT_OVERRIDE_ANNOTATION)
    }
  }

  /**
   * The members that Implement Methods selects when it opens: those it lists first, without the methods of
   * `java.lang.Record` that a record implements by itself unless nothing else is left (`prepare`'s `selectElements`).
   */
  private def preselected(
      shown: Seq[PsiMethodMember], primary: Seq[CandidateInfo], clazz: PsiClass)
      : Seq[PsiMethodMember] = {
    val required = shown.filter(member => primary.exists(_.getElement == member.getElement))
    val own = required.filterNot(member => OverrideImplementExploreUtil.belongsToRecord(member.getElement))

    if (clazz.isRecord && own.nonEmpty) own else required
  }

  /**
   * Orders Java methods as the dialog of the Java plugin lists them: the owners in the order in which the candidates
   * first name them, which is how `MemberChooser.buildModel` creates its nodes, and within an owner by position in the
   * source (`MemberChooser.OrderComparator`), or in the candidates' own order for a compiled owner, which has no
   * positions.
   */
  private def javaChooserOrder(members: Seq[PsiMethodMember]): Seq[PsiMethodMember] =
    members.map(_.getElement.getContainingClass).distinct.flatMap { owner =>
      val own = members.filter(_.getElement.getContainingClass == owner)

      if (own.head.getElement.isInstanceOf[PsiCompiledElement]) own else own.sortBy(_.getElement.getTextOffset)
    }

  /**
   * Inserts the methods into the Java class at the editor's caret as the dialog's OK does: the plugin's
   * `OverrideImplementUtil.overrideOrImplementMethodsInRightPlace`, which places them by the caret and builds their
   * bodies from the body templates.
   */
  private def insertJava(members: Seq[PsiMethodMember], listing: JavaListing, editor: Editor): Unit =
    // The last argument is the dialog's `@Override` checkbox; Javadoc is not copied.
    OverrideImplementUtil.overrideOrImplementMethodsInRightPlace(
      editor, listing.clazz, members.asJava, false, listing.insertOverride)

  /** The members in the chooser's order (see [[ownerGroups]]). */
  private def inChooserOrder(members: Seq[ClassMember], clazz: ScTemplateDefinition): Seq[ClassMember] =
    ownerGroups(members, clazz).flatten

  /**
   * The members as the plugin inserts them: those of one extension of Scala 3 become a single member, which the plugin
   * inserts as the extension (`ScalaMemberChooser.sortedWithExtensionMethodsGrouped`).
   */
  private def withExtensionsGrouped(members: Seq[ClassMember], clazz: ScTemplateDefinition): Seq[ClassMember0] =
    ownerGroups(members, clazz).flatMap { group =>
      val byExtension = group.groupBy { member =>
        extensionMethod(member).flatMap(_.signature.extensionSignature).map(_.extension)
      }
      val grouped = byExtension.toSeq.flatMap {
        case (Some(extension), methods) =>
          Seq(extensionMember(extension, methods.flatMap(extensionMethod).sortBy(_.getElement.getTextOffset)))
        case (None, others) => others
      }

      grouped.sortBy(_.getElement.getTextOffset)
    }

  /**
   * The member as an extension method, if it is one. These tests take `Any`: with the member's own type the compiler
   * checks the abstract members of `MemberChooserObject`, whose signature needs a UI class that the host does not have.
   */
  private def extensionMethod(member: Any): Option[ScExtensionMethodMember] = member match {
    case method: ScExtensionMethodMember => Some(method)
    case _ => None
  }

  /** Whether the member gets the `override` modifier: it overrides, or the settings add it to implemented members. */
  private def overridesInherited(member: Any): Boolean = member match {
    case overridable: ScalaOverridableMember =>
      overridable.isOverride || ScalaGenerationInfo.toAddOverrideToImplemented
    case _ => false
  }

  /**
   * The plugin's `ScExtensionMember`, made reflectively, for the reason of [[extensionMethod]].
   */
  private def extensionMember(extension: ScExtension, methods: Seq[ScExtensionMethodMember]): ClassMember0 =
    // The class is a `ScalaMember` with an element, which is what `ClassMember0` says.
    classOf[ScExtensionMember].getConstructors.head.newInstance(extension, methods).asInstanceOf[ClassMember0]

  /**
   * Inserts the members into the class at the editor's caret, as the actions do once the chooser is confirmed.
   *
   * This is the plugin's `ScalaGenerateMembersUtil.insertMembersAtCaretPosition`, which needs the chooser's sorting
   * (see [[ownerGroups]]) and has no other step that the host would have to leave out. The plugin inserts the last
   * member first, so that the anchors of the others stay valid.
   */
  private def insert(members: Seq[ClassMember], clazz: ScTemplateDefinition, editor: Editor): Unit = {
    val adjuster = TypeAdjusterClass.getConstructor().newInstance()
    val infos = withExtensionsGrouped(members, clazz).reverse.map { member =>
      reflected(NewGenerationInfo.newInstance(member, Boolean.box(overridesInherited(member)), adjuster))
    }

    GenerateMembersUtil.insertMembersAtOffset(clazz, ScalaEditorUtils.caretOffsetWithFixedEof(editor), infos.asJava)
    reflected(TypeAdjusterClass.getMethod("adjustTypes").invoke(adjuster))
  }

  /** Runs a reflective call, failing with what the called code threw rather than with the reflection's wrapper. */
  private def reflected[A](call: => A): A =
    try call
    catch { case error: InvocationTargetException => throw error.getCause }

  /**
   * The chooser's rows for members: each member's text and its owner's name, with the two together as its identity.
   *
   * Overloads whose parameter types differ only in their packages, such as `f(x: a.T)` and `f(x: b.T)`, show the same
   * text, so the rows that share an identity are numbered in the chooser's order, which within an owner is the source
   * order. Only such rows are numbered: an overload that appears or disappears after the listing then makes the
   * identifiers of the others unknown, rather than one of them silently that of another member.
   *
   * @param chooser the members in the chooser's order
   * @return one row per member, in the same order
   */
  private def rows(chooser: Seq[ChooserMember]): Seq[Member] = {
    val shown = chooser.map(member => (member.getParentNodeDelegate.getText, member.getText))
    val sharing = shown.groupMapReduce(identity)(_ => 1)(_ + _)

    shown.zipWithIndex.map { case (row @ (owner, signature), index) =>
      val key = s"$owner#$signature"
      val id = if (sharing(row) == 1) key else s"$key@${shown.take(index).count(_ == row) + 1}"

      Member(id, signature, owner)
    }
  }

  /** The editor of the running preview session, with its caret moved to `offset`. */
  private def previewEditorAt(offset: Int): Editor = {
    val editor = IntentionPreviewUtils.getPreviewEditor

    editor.getCaretModel.moveToOffset(offset)
    editor
  }

  /** Runs `body` with an editor whose caret is at `offset` of the file's document. */
  private def atCaret[A](file: PsiFile, offset: Int)(body: Editor => A): A =
    Using.resource(new RequestEditor(file.getViewProvider.getDocument, file.getProject, offset)) { request =>
      body(request.editor)
    }
}
