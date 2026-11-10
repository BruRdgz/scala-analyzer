package scalaanalyzer.language

import com.intellij.codeInsight.generation.GenerateMembersUtil
import com.intellij.codeInsight.intention.preview.IntentionPreviewUtils
import com.intellij.openapi.editor.Editor
import com.intellij.psi.{PsiClass, PsiFile}
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
   * @param file Scala source being queried
   * @param offset UTF-16 caret offset
   * @return `true` when the caret is in a class, object or trait that has abstract members left to implement
   */
  def hasMembersToImplement(file: ScalaFile, offset: Int): Boolean =
    atCaret(file, offset)(editor => listing(file, editor, implement = true).exists(_.members.nonEmpty))

  /**
   * Previews the insertion of every member the class at the offset has to implement, the chooser's default selection.
   *
   * Takes read access itself and runs on the host's dispatch thread (see [[inserting]]), so the caller must not be
   * reading or editing; the file is unchanged.
   *
   * @param file Scala source to edit
   * @param offset UTF-16 caret offset; nothing is inserted when it is not in a class, object or trait
   * @return the changed text and the edits against the current text of `file`
   */
  def implementAll(file: ScalaFile, offset: Int): ChangeEngine.Result = inserting {
    ChangeEngine.run(file) { copy =>
      val editor = previewEditorAt(offset)

      listing(copy, editor, implement = true).foreach(found => insert(found.members, found.clazz, editor))
    }._1
  }

  /**
   * Lists the members Override Members offers for the class at the offset.
   *
   * The caller holds the host read lock; the file is unchanged.
   *
   * @param file Scala source being queried
   * @param offset UTF-16 caret offset
   * @return the chooser's members in its order, grouped by owner as the chooser does; empty when the caret is not in
   *         a class, object or trait, or nothing is left to override
   */
  def overridable(file: ScalaFile, offset: Int): Seq[Member] = atCaret(file, offset) { editor =>
    listing(file, editor, implement = false).toSeq.flatMap(found => rows(found.members))
  }

  /**
   * Computes the edits that override the chosen members in the class at the offset.
   *
   * Takes read access itself and runs on the host's dispatch thread (see [[inserting]]), so the caller must not be
   * reading or editing; the file is unchanged.
   *
   * @param file Scala source to edit
   * @param offset UTF-16 caret offset, where the members are inserted
   * @param ids identifiers of the members to override, as [[overridable]] gave them
   * @return the edits against the current text of `file`
   * @throws UnknownMembers when an identifier is not that of a member the class offers
   */
  def overrideMembers(file: ScalaFile, offset: Int, ids: Seq[String]): Seq[ChangeEngine.Edit] = inserting {
    ChangeEngine.run(file) { copy =>
      val editor = previewEditorAt(offset)
      val offered = listing(copy, editor, implement = false)
      val byId = offered.toSeq.flatMap(found => rows(found.members).map(_.id).zip(found.members)).toMap
      val unknown = ids.filterNot(byId.contains)

      if (unknown.nonEmpty) throw new UnknownMembers(unknown)
      offered.foreach(found => insert(ids.distinct.map(byId), found.clazz, editor))
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
  private def rows(chooser: Seq[ClassMember]): Seq[Member] = {
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
