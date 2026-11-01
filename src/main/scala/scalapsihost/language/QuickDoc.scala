package scalapsihost.language

import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.{HTML, HTMLEditorKit}
import javax.swing.text.html.parser.ParserDelegator

import java.io.StringReader

import scala.collection.mutable.ArrayBuffer

/**
 * Converts the plugin's quick-documentation HTML into editor-readable Markdown.
 *
 * Only documentation content and sections are retained; signatures and location lines are presented separately.
 */
object QuickDoc {

  /**
   * Converts documentation content and sections from the plugin's HTML output.
   *
   * This expects the generator's `content` and `sections` fragments. IDE-only links are omitted while their labels
   * remain; HTTP and HTTPS links are retained.
   *
   * @param html quick-documentation HTML produced by the plugin
   * @param codeLanguage default language of code blocks without an explicit language attribute
   * @return Markdown content, or none when the selected fragments contain no text
   */
  def toMarkdown(html: String, codeLanguage: String = "scala"): Option[String] = {
    val converter = new Converter(codeLanguage)
    new ParserDelegator().parse(new StringReader(html), converter, true)
    converter.markdown
  }

  /** Characters Markdown gives a meaning to, which prose must not trigger. */
  private val MarkdownSignificant = "\\*_[]<>#`"

  private val NonBreakingSpace = '\u00a0'

  /** The generator's content and section fragments that survive conversion. */
  private enum Region {
    case Outside, Content, Sections
  }

  /** Tracks one HTML list's marker style and ordered-list numbering. */
  private final class ListLevel(val ordered: Boolean) {
    var items = 0
  }

  /** Converts the documentation generator's HTML fragments to Markdown as the parser visits them. */
  private final class Converter(codeLanguage: String) extends HTMLEditorKit.ParserCallback {
    private val content = new StringBuilder
    private val sections = ArrayBuffer.empty[String]
    private var region = Region.Outside
    // Tags like the region's own opened inside it (a table in the content): their end does not end the region.
    private var nestedInRegion = 0
    // Where the current text goes: `content`, a section's name or entry, or a buffer nobody reads (outside the doc's parts).
    private var out = new StringBuilder

    private var lists = List.empty[ListLevel]
    private var pre = Option.empty[StringBuilder]
    // A Markdown code block names its language (`<code class="language-java">`); a wiki one ({{{ }}}) is Scala.
    private var preLanguage = codeLanguage
    private var code = Option.empty[StringBuilder]
    private var linkTargets = List.empty[Option[String]]

    private var sectionName = ""
    private val sectionEntries = ArrayBuffer.empty[String]

    /** The assembled Markdown, absent when the selected fragments contain no text. */
    def markdown: Option[String] = {
      val parts = Seq(content.toString.trim, sections.mkString("\n\n")).filter(_.nonEmpty)
      Option.when(parts.nonEmpty)(parts.mkString("\n\n"))
    }

    override def handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, pos: Int): Unit = tag match {
      case HTML.Tag.DIV if attribute(attributes, HTML.Attribute.CLASS).contains("content") => enter(Region.Content, content)
      case HTML.Tag.TABLE if attribute(attributes, HTML.Attribute.CLASS).contains("sections") => enter(Region.Sections, new StringBuilder)
      case HTML.Tag.DIV | HTML.Tag.TABLE if regionTag.contains(tag) => nestedInRegion += 1
      case HTML.Tag.TD if region == Region.Sections => out = new StringBuilder
      case HTML.Tag.P if region == Region.Content => endParagraph()
      case HTML.Tag.P if region == Region.Sections => finishSectionEntry()
      case HTML.Tag.B | HTML.Tag.STRONG => out.append("**")
      case HTML.Tag.I | HTML.Tag.EM => out.append("*")
      case HTML.Tag.CODE | HTML.Tag.TT if pre.isEmpty => code = Some(new StringBuilder)
      case HTML.Tag.CODE =>
        attribute(attributes, HTML.Attribute.CLASS).collect { case s"language-$language" => language }.foreach(preLanguage = _)
      case HTML.Tag.PRE =>
        endParagraph()
        pre = Some(new StringBuilder)
        preLanguage = codeLanguage
      case HTML.Tag.UL | HTML.Tag.OL =>
        if (lists.isEmpty) endParagraph() else endLine()
        lists = new ListLevel(tag == HTML.Tag.OL) :: lists
      case HTML.Tag.LI if lists.nonEmpty => startListItem(lists.head)
      case HTML.Tag.H1 | HTML.Tag.H2 | HTML.Tag.H3 | HTML.Tag.H4 | HTML.Tag.H5 | HTML.Tag.H6 =>
        endParagraph()
        out.append("#" * (tag.toString.last - '0')).append(' ')
      case HTML.Tag.A =>
        // The IDE's own links (psi_element://) open nothing in an editor.
        val target = attribute(attributes, HTML.Attribute.HREF).filter(_.matches("(?i)https?://.*"))
        target.foreach(_ => out.append('['))
        linkTargets = target :: linkTargets
      case _ =>
    }

    override def handleEndTag(tag: HTML.Tag, pos: Int): Unit = tag match {
      case HTML.Tag.DIV | HTML.Tag.TABLE if regionTag.contains(tag) =>
        if (nestedInRegion > 0) nestedInRegion -= 1 else enter(Region.Outside, new StringBuilder)
      case HTML.Tag.TD if region == Region.Sections => endSectionCell()
      case HTML.Tag.P if region == Region.Content => endParagraph()
      case HTML.Tag.B | HTML.Tag.STRONG => out.append("**")
      case HTML.Tag.I | HTML.Tag.EM => out.append("*")
      case HTML.Tag.CODE | HTML.Tag.TT if pre.isEmpty => endInlineCode()
      case HTML.Tag.PRE => endCodeBlock()
      case HTML.Tag.UL | HTML.Tag.OL if lists.nonEmpty =>
        lists = lists.tail
        if (lists.isEmpty) endParagraph()
      case HTML.Tag.H1 | HTML.Tag.H2 | HTML.Tag.H3 | HTML.Tag.H4 | HTML.Tag.H5 | HTML.Tag.H6 => endParagraph()
      case HTML.Tag.A if linkTargets.nonEmpty =>
        linkTargets.head.foreach(target => out.append("](").append(target).append(')'))
        linkTargets = linkTargets.tail
      case _ =>
    }

    override def handleSimpleTag(tag: HTML.Tag, attributes: MutableAttributeSet, pos: Int): Unit =
      if (tag == HTML.Tag.BR) {
        pre match {
          case Some(block) => block.append('\n')
          case None => out.append("\\\n")
        }
      }

    override def handleText(data: Array[Char], pos: Int): Unit = {
      val text = new String(data).replace(NonBreakingSpace, ' ')
      (pre, code) match {
        case (Some(block), _) => block.append(text)
        case (None, Some(span)) => span.append(collapseSpaces(text))
        case (None, None) => appendProse(text)
      }
    }

    /** Reads an HTML attribute as text, treating a missing attribute as absent. */
    private def attribute(attributes: MutableAttributeSet, name: HTML.Attribute): Option[String] =
      Option(attributes.getAttribute(name)).map(_.toString)

    /** The outer HTML tag whose closing ends the current content or sections fragment. */
    private def regionTag: Option[HTML.Tag] = region match {
      case Region.Content => Some(HTML.Tag.DIV)
      case Region.Sections => Some(HTML.Tag.TABLE)
      case Region.Outside => None
    }

    /** Routes subsequent parser text to a selected fragment buffer. */
    private def enter(entered: Region, target: StringBuilder): Unit = {
      region = entered
      out = target
    }

    /** Ends the current Markdown line without adding a second newline. */
    private def endLine(): Unit = {
      stripTrailing(' ')
      if (out.nonEmpty && out.last != '\n') out.append('\n')
    }

    /** Trims trailing whitespace and separates nonempty Markdown paragraphs. */
    private def endParagraph(): Unit = {
      while (out.nonEmpty && out.last.isWhitespace) out.setLength(out.length - 1)
      if (out.nonEmpty) out.append("\n\n")
    }

    /** Removes a run of the requested character from the current output's end. */
    private def stripTrailing(c: Char): Unit =
      while (out.nonEmpty && out.last == c) out.setLength(out.length - 1)

    /** Collapses HTML whitespace runs to one space before converting prose or inline code. */
    private def collapseSpaces(text: String): String = text.replaceAll("\\s+", " ")

    /** Appends prose with HTML whitespace semantics and escapes Markdown-significant characters. */
    private def appendProse(text: String): Unit = {
      val spaced = collapseSpaces(text)
      val atLineStart = out.isEmpty || out.last == '\n' || out.last == ' '
      val trimmed = if (atLineStart) spaced.stripLeading else spaced
      trimmed.foreach { character =>
        if (MarkdownSignificant.contains(character)) out.append('\\')
        out.append(character)
      }
    }

    /** Writes the next item marker, indenting it according to the current list nesting. */
    private def startListItem(list: ListLevel): Unit = {
      list.items += 1
      endLine()
      val marker = if (list.ordered) s"${list.items}." else "-"
      out.append("  " * (lists.size - 1)).append(marker).append(' ')
    }

    /** Emits an inline code span with a fence that cannot occur inside its text. */
    private def endInlineCode(): Unit = code.foreach { span =>
      val text = span.toString
      val longestRun = text.split("[^`]+").map(_.length).maxOption.getOrElse(0)
      val fence = "`" * (longestRun + 1)
      // A code span's edge backtick would join the fence.
      val padding = if (text.startsWith("`") || text.endsWith("`")) " " else ""
      out.append(fence).append(padding).append(text).append(padding).append(fence)
      code = None
    }

    /** Emits the current preformatted block as a fenced Markdown block. */
    private def endCodeBlock(): Unit = pre.foreach { block =>
      val text = block.toString.dropWhile(_ == '\n').stripTrailing
      out.append("```").append(preLanguage).append('\n').append(text).append("\n```")
      endParagraph()
      pre = None
    }

    /** Finishes a section name or emits the entries collected from its value cell. */
    private def endSectionCell(): Unit =
      if (sectionName.isEmpty) sectionName = out.toString.trim
      else {
        finishSectionEntry()
        sections += sectionBlock(sectionName, sectionEntries.toSeq)
        sectionName = ""
        sectionEntries.clear()
      }

    /** Stores the current nonempty section entry and resets output for the next cell. */
    private def finishSectionEntry(): Unit = {
      val entry = out.toString.trim
      if (sectionName.nonEmpty && entry.nonEmpty) sectionEntries += entry
      out = new StringBuilder
    }

    /** Formats one documentation section, keeping a single entry inline and listing multiple entries. */
    private def sectionBlock(name: String, entries: Seq[String]): String = entries match {
      case Seq(single) => s"**$name** $single"
      case _ => entries.map("- " + _).mkString(s"**$name**\n", "\n", "")
    }
  }
}
