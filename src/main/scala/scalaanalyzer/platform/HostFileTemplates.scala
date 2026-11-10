package scalaanalyzer.platform

import com.intellij.ide.fileTemplates.{FileTemplate, FileTemplateManager}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.Properties

import scala.util.Using
import scala.util.matching.Regex

/**
 * The IDE's code templates, as far as the plugin's generated members need them: the body templates of implemented
 * and overridden methods (`???`, `super.name(...)`).
 *
 * The host has no template settings, so a template is the default the plugin bundles, read from the classpath. It is
 * rendered by replacing each `$NAME` and `${NAME}` reference with the property the caller sets under that name; the
 * Velocity directives (`#if`, `#parse`) that other templates use are not supported.
 */
private[scalaanalyzer] object HostFileTemplates {
  /** A Velocity reference: `$NAME` or `${NAME}`. */
  private val Reference = """\$(?:\{(\w+)\}|(\w+))""".r

  /**
   * Creates the manager that the project's `FileTemplateManager` service answers with.
   *
   * @return a manager that finds the bundled code templates by name; any other call, and a template the plugin does
   *         not bundle, is recorded and rejected by [[CallLog]]
   */
  def manager: FileTemplateManager = new Host_FileTemplateManager(dispatch {
    case ("FileTemplateManager.getCodeTemplate", Array(name: String)) => bundled(name)
  })

  /** The template the plugin bundles as `fileTemplates/code/<name>.ft`, which can only be rendered. */
  private def bundled(name: String): FileTemplate = {
    val stream = Option(getClass.getClassLoader.getResourceAsStream(s"fileTemplates/code/$name.ft"))
      .getOrElse(CallLog.unsupported(s"FileTemplateManager.getCodeTemplate $name"))
    val text = Using.resource(stream)(in => new String(in.readAllBytes, UTF_8))

    tableProxy(classOf[FileTemplate], "FileTemplate") {
      case ("getText", Array(properties: Properties)) => render(text, properties)
    }
  }

  /** `text` with each reference replaced by the property of its name; a reference without one stays as written. */
  private def render(text: String, properties: Properties): String =
    Reference.replaceAllIn(text, found => {
      val name = Option(found.group(1)).getOrElse(found.group(2))

      Regex.quoteReplacement(Option(properties.getProperty(name)).getOrElse(found.matched))
    })
}
