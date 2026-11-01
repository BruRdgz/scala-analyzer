package scalapsihost.language

import javax.swing.text.MutableAttributeSet
import javax.swing.text.html.{HTML, HTMLEditorKit}
import javax.swing.text.html.parser.ParserDelegator

import java.io.StringReader

import scala.collection.mutable.{ArrayBuffer, LinkedHashMap}

/**
 * Converts the plugin's quick-documentation HTML into editor-readable Markdown.
 *
 * Retains declaration context, documentation blocks and grouped sections; the hover supplies its signature separately.
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
  private val ListedSections = Set("Params", "Parameters", "Type parameters", "Throws", "See also")

  /** The generator's content and section fragments that survive conversion. */
  private enum Region {
    case Outside, Location, Content, Sections
  }

  /** Tracks one HTML list's marker style and ordered-list numbering. */
  private final class ListLevel(val ordered: Boolean, val indent: Int) {
    var items = 0
    var contentIndent = indent
  }

  /** Collects an HTML table independently of its surrounding paragraph or section cell. */
  private final class Table(val parent: StringBuilder) {
    val rows = ArrayBuffer.empty[Vector[String]]
    val cells = ArrayBuffer.empty[String]
    var hasHeader = false
  }

  /** Chooses a backtick fence longer than any run in the literal code. */
  private def codeFence(text: String, minimum: Int): String =
    "`" * math.max(minimum, text.split("[^`]+").map(_.length).maxOption.getOrElse(0) + 1)

  /** Converts the documentation generator's HTML fragments to Markdown as the parser visits them. */
  private final class Converter(codeLanguage: String) extends HTMLEditorKit.ParserCallback {
    private val content = new StringBuilder
    private val location = new StringBuilder
    private val sections = LinkedHashMap.empty[String, ArrayBuffer[String]]
    private var tables = List.empty[Table]
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
      val context = Option(location.toString.trim).filter(_.nonEmpty).map("**Defined in:** " + _)
      val renderedSections = sections.iterator.map((name, entries) => sectionBlock(name, entries.toSeq)).mkString("\n\n")
      val parts = context.toSeq ++ Seq(content.toString.trim, renderedSections).filter(_.nonEmpty)
      Option.when(parts.nonEmpty)(parts.mkString("\n\n"))
    }

    override def handleStartTag(tag: HTML.Tag, attributes: MutableAttributeSet, pos: Int): Unit = tag match {
      case HTML.Tag.DIV if hasClass(attributes, "content") && region == Region.Outside => enter(Region.Content, content)
      case HTML.Tag.DIV if hasClass(attributes, "bottom") && region == Region.Outside => enter(Region.Location, location)
      case HTML.Tag.TABLE if hasClass(attributes, "sections") && region == Region.Outside => enter(Region.Sections, new StringBuilder)
      case _ if region == Region.Outside => ()
      case HTML.Tag.TABLE =>
        endParagraph()
        tables = new Table(out) :: tables
        out = new StringBuilder
      case HTML.Tag.TR if tables.nonEmpty => tables.head.cells.clear()
      case HTML.Tag.TD | HTML.Tag.TH if tables.nonEmpty =>
        if (tag == HTML.Tag.TH && tables.head.rows.isEmpty) tables.head.hasHeader = true
        out = new StringBuilder
      case HTML.Tag.DIV if regionTag.contains(tag) =>
        nestedInRegion += 1
        endParagraph()
      case HTML.Tag.TD if region == Region.Sections => out = new StringBuilder
      case HTML.Tag.P if region == Region.Sections && tables.isEmpty =>
        if (ListedSections(sectionName.stripSuffix(":").trim)) finishSectionEntry() else endParagraph()
      case HTML.Tag.P | HTML.Tag.DIV => endParagraph()
      case HTML.Tag.B | HTML.Tag.STRONG =>
        indentContinuation()
        out.append("**")
      case HTML.Tag.I | HTML.Tag.EM =>
        indentContinuation()
        out.append("*")
      case HTML.Tag.CODE | HTML.Tag.TT if pre.isEmpty => code = Some(new StringBuilder)
      case HTML.Tag.CODE =>
        attribute(attributes, HTML.Attribute.CLASS).collect { case s"language-$language" => language }.foreach(preLanguage = _)
      case HTML.Tag.PRE =>
        endParagraph()
        pre = Some(new StringBuilder)
        preLanguage = codeLanguage
      case HTML.Tag.UL | HTML.Tag.OL =>
        if (lists.isEmpty) endParagraph() else endLine()
        lists = new ListLevel(tag == HTML.Tag.OL, lists.headOption.fold(0)(_.contentIndent)) :: lists
      case HTML.Tag.LI if lists.nonEmpty => startListItem(lists.head)
      case HTML.Tag.H1 | HTML.Tag.H2 | HTML.Tag.H3 | HTML.Tag.H4 | HTML.Tag.H5 | HTML.Tag.H6 =>
        endParagraph()
        out.append("#" * (tag.toString.last - '0')).append(' ')
      case HTML.Tag.A =>
        // The IDE's own links (psi_element://) open nothing in an editor.
        val target = attribute(attributes, HTML.Attribute.HREF).filter(_.matches("(?i)https?://.*"))
          .map(_.replace("\\", "%5C").replace("(", "%28").replace(")", "%29").replace(" ", "%20"))
        target.foreach { _ =>
          indentContinuation()
          out.append('[')
        }
        linkTargets = target :: linkTargets
      case _ =>
    }

    override def handleEndTag(tag: HTML.Tag, pos: Int): Unit = tag match {
      case _ if region == Region.Outside => ()
      case HTML.Tag.TD | HTML.Tag.TH if tables.nonEmpty => tables.head.cells += out.toString.trim
      case HTML.Tag.TR if tables.nonEmpty && tables.head.cells.nonEmpty =>
        tables.head.rows += tables.head.cells.toVector
        tables.head.cells.clear()
      case HTML.Tag.TABLE if tables.nonEmpty => endTable()
      case HTML.Tag.DIV | HTML.Tag.TABLE if regionTag.contains(tag) =>
        endParagraph()
        if (nestedInRegion > 0) nestedInRegion -= 1 else enter(Region.Outside, new StringBuilder)
      case HTML.Tag.TD if region == Region.Sections => endSectionCell()
      case HTML.Tag.P | HTML.Tag.DIV => endParagraph()
      case HTML.Tag.B | HTML.Tag.STRONG => out.append("**")
      case HTML.Tag.I | HTML.Tag.EM => out.append("*")
      case HTML.Tag.CODE | HTML.Tag.TT if pre.isEmpty => endInlineCode()
      case HTML.Tag.PRE => endCodeBlock()
      case HTML.Tag.UL | HTML.Tag.OL if lists.nonEmpty =>
        lists = lists.tail
        if (lists.isEmpty) endParagraph() else endLine()
      case HTML.Tag.H1 | HTML.Tag.H2 | HTML.Tag.H3 | HTML.Tag.H4 | HTML.Tag.H5 | HTML.Tag.H6 => endParagraph()
      case HTML.Tag.A if linkTargets.nonEmpty =>
        linkTargets.head.foreach(target => out.append("](").append(target).append(')'))
        linkTargets = linkTargets.tail
      case _ =>
    }

    override def handleSimpleTag(tag: HTML.Tag, attributes: MutableAttributeSet, pos: Int): Unit =
      if (tag == HTML.Tag.BR && region != Region.Outside) {
        pre match {
          case Some(block) => block.append('\n')
          case None if tables.nonEmpty => out.append("<br>")
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

    /** Tests a CSS class token without requiring it to be the element's only class. */
    private def hasClass(attributes: MutableAttributeSet, name: String): Boolean =
      attribute(attributes, HTML.Attribute.CLASS).exists(_.split("\\s+").contains(name))

    /** The outer HTML tag whose closing ends the current content or sections fragment. */
    private def regionTag: Option[HTML.Tag] = region match {
      case Region.Content | Region.Location => Some(HTML.Tag.DIV)
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
      if (trimmed.nonEmpty) indentContinuation()
      trimmed.foreach { character =>
        if (MarkdownSignificant.contains(character)) out.append('\\')
        out.append(character)
      }
    }

    /** Keeps new paragraphs within the current list item even when they start with inline markup. */
    private def indentContinuation(): Unit =
      if (out.nonEmpty && out.last == '\n' && lists.nonEmpty) out.append(" " * lists.head.contentIndent)

    /** Writes the next item marker, indenting it according to the current list nesting. */
    private def startListItem(list: ListLevel): Unit = {
      list.items += 1
      endLine()
      val marker = if (list.ordered) s"${list.items}." else "-"
      out.append(" " * list.indent).append(marker).append(' ')
      list.contentIndent = list.indent + marker.length + 1
    }

    /** Emits an inline code span with a fence that cannot occur inside its text. */
    private def endInlineCode(): Unit = code.foreach { span =>
      val text = span.toString
      val fence = codeFence(text, minimum = 1)
      // A code span's edge backtick would join the fence.
      val padding = if (text.startsWith("`") || text.endsWith("`")) " " else ""
      indentContinuation()
      out.append(fence).append(padding).append(text).append(padding).append(fence)
      code = None
    }

    /** Emits a collision-free code fence with the indentation required by its surrounding list. */
    private def endCodeBlock(): Unit = pre.foreach { block =>
      val text = block.toString.dropWhile(_ == '\n').stripTrailing
      val fence = codeFence(text, minimum = 3)
      val indent = " " * lists.headOption.fold(0)(_.contentIndent)
      val blockText = s"$fence$preLanguage\n$text\n$fence"
      out.append(blockText.linesIterator.map(indent + _).mkString("\n"))
      endParagraph()
      pre = None
    }

    /** Finishes a section name or emits the entries collected from its value cell. */
    private def endSectionCell(): Unit =
      if (sectionName.isEmpty) sectionName = out.toString.trim
      else {
        finishSectionEntry()
        val title = sectionName.stripSuffix(":").trim match {
          case "Params" => "Parameters"
          case other => other
        }
        if (sectionEntries.nonEmpty) sections.getOrElseUpdate(title, ArrayBuffer.empty) ++= sectionEntries
        sectionName = ""
        sectionEntries.clear()
      }

    /** Stores the current nonempty section entry and resets output for the next cell. */
    private def finishSectionEntry(): Unit = {
      val entry = out.toString.trim
      if (sectionName.nonEmpty && entry.nonEmpty) sectionEntries += entry
      out = new StringBuilder
    }

    /** Renders a table as GFM, adding an empty header when the HTML has only data cells. */
    private def endTable(): Unit = {
      val table = tables.head
      tables = tables.tail
      out = table.parent
      if (table.rows.nonEmpty) {
        val width = table.rows.map(_.size).max
        val rows = if (table.hasHeader) table.rows.toSeq else Vector.fill(width)("") +: table.rows.toSeq
        val rendered = rows.map { row =>
          row.padTo(width, "").map(_.replace("|", "\\|").replace("\n", "<br>"))
            .mkString("| ", " | ", " |")
        }
        val separator = Vector.fill(width)("---").mkString("| ", " | ", " |")
        out.append((Seq(rendered.head, separator) ++ rendered.tail).mkString("\n"))
        endParagraph()
      }
    }

    /** Keeps section headings separate and renders named entries as a consistent list. */
    private def sectionBlock(name: String, entries: Seq[String]): String = {
      val listed = ListedSections(name) || entries.size > 1
      val body = if (listed) entries.map(entry => "- " + entry.replace("\n", "\n  ")).mkString("\n")
                 else entries.mkString("\n\n")
      s"### $name\n\n$body"
    }
  }
}
