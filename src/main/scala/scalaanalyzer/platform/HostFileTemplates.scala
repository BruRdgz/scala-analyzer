package scalaanalyzer.platform

import com.intellij.ide.fileTemplates.{FileTemplate, FileTemplateManager}
import com.intellij.openapi.util.io.FileUtilRt

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Properties

import scala.util.Using
import scala.util.matching.Regex

/**
 * The IDE's code templates, as far as the plugin's generated members need them: the body templates of implemented
 * and overridden methods (`???`, `super.name(...)`, and the Java plugin's `return 0;`).
 *
 * The host has no template settings, so a template is the default the plugin bundles, read from the classpath. It is
 * rendered as the IDE's Velocity engine does for the subset that those templates use: each `$NAME` and `${NAME}`
 * reference is replaced with the property the caller sets under that name, and `#if ( $NAME == "text" )body#end` (or
 * `!=`) keeps its body when the property's text is (or is not) equal to the literal. Any other directive (`#else`,
 * `#foreach`, `#parse`, `#set`, a comment, a condition of another form) makes the template unsupported when it is
 * looked up, rather than render as something else.
 */
private[scalaanalyzer] object HostFileTemplates {
  /** A Velocity reference: `$NAME` or `${NAME}`. */
  private val Reference = """\$(?:\{(\w+)\}|(\w+))""".r

  /** The conditional that is supported: a reference compared with a literal, around a body without directives. */
  private val Conditional =
    """#if\s*\(\s*\$(?:\{(\w+)\}|(\w+))\s*(==|!=)\s*"([^"]*)"\s*\)([^#]*)#end""".r

  /** The start of a Velocity directive, comment or escape: what no template the host renders may have left. */
  private val Directive = """#(?:#|\*|\[\[|\{?[A-Za-z]\w*)""".r

  /**
   * Creates the manager that the project's `FileTemplateManager` service answers with.
   *
   * @return a manager that finds the bundled code templates by name; any other call, a template the plugin does not
   *         bundle, and a bundled template that uses a directive this object does not render, is recorded and
   *         rejected by [[CallLog]]
   */
  def manager: FileTemplateManager = new Host_FileTemplateManager(dispatch {
    case ("FileTemplateManager.getCodeTemplate", Array(name: String)) => bundled(name)
    // The IDE's defaults are the date, the user and the project, none of which a body template uses.
    case ("FileTemplateManager.getDefaultProperties", Array()) => new Properties()
  })

  /**
   * The first Velocity directive, comment or escape of a template that [[render]] cannot render.
   *
   * @param text a template, as the plugin bundles it
   * @return the start of that directive, such as `#foreach`; none when the template has only references and the
   *         conditionals that [[render]] supports
   */
  private[scalaanalyzer] def unsupportedDirective(text: String): Option[String] =
    Directive.findFirstIn(Conditional.replaceAllIn(text, ""))

  /** The template the plugin bundles as `fileTemplates/code/<name>.ft`, which can only be rendered. */
  private def bundled(name: String): FileTemplate = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(s"fileTemplates/code/$name.ft"))
      .getOrElse(CallLog.unsupported(s"FileTemplateManager.getCodeTemplate $name"))
    val text = Using.resource(stream)(in => new String(in.readAllBytes, UTF_8))

    unsupportedDirective(text).foreach { directive =>
      CallLog.unsupported(s"FileTemplateManager.getCodeTemplate $name uses the Velocity directive $directive")
    }

    tableProxy(classOf[FileTemplate], "FileTemplate") {
      // Properties is a Map: this answers both overloads of the interface.
      case ("getText", Array(properties: java.util.Map[?, ?])) => render(name, text, properties)
      case ("getExtension", Array()) => FileUtilRt.getExtension(name)
    }
  }

  /**
   * `text` with each supported conditional replaced by its body or by nothing, and then each reference replaced by the
   * property of its name; a reference without one stays as written.
   */
  private def render(name: String, text: String, properties: java.util.Map[?, ?]): String = {
    def property(reference: String): Option[String] = Option(properties.get(reference)).map(_.toString)

    val chosen = Conditional.replaceAllIn(text, found => {
      val reference = Option(found.group(1)).getOrElse(found.group(2))
      val value = property(reference).getOrElse(
        CallLog.unsupported(s"FileTemplate.getText $name compares the unset property $reference"))
      val equal = value == found.group(4)
      val kept = if ((found.group(3) == "==") == equal) found.group(5) else ""

      Regex.quoteReplacement(kept)
    })

    Reference.replaceAllIn(chosen, found => {
      val reference = Option(found.group(1)).getOrElse(found.group(2))

      Regex.quoteReplacement(property(reference).getOrElse(found.matched))
    })
  }
}
