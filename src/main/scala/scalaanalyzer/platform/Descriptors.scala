package scalaanalyzer.platform

import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.util.registry.RegistryKeyDescriptor
import javax.xml.parsers.DocumentBuilderFactory

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.zip.ZipFile

import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * An extension-point declaration read from descriptor XML.
 *
 * @param name fully qualified extension-point name
 * @param className interface or bean class named by the descriptor
 * @param kind whether extensions implement an interface or populate a bean
 * @param dynamic whether the descriptor allows dynamic registration
 * @param fromScalaPlugin whether the declaration came from a Scala plugin jar
 */
final case class EpDecl(name: String, className: String, kind: ExtensionPoint.Kind, dynamic: Boolean, fromScalaPlugin: Boolean)

/**
 * Descriptor data retained for a bean extension, including ordering constraints.
 *
 * @param attributes XML attributes on the extension element
 * @param children text of direct child elements, keyed by tag name
 */
final case class ExtensionBean(attributes: Map[String, String], children: Map[String, String] = Map.empty) {
  /**
   * Resolves the implementation class from the supported descriptor fields.
   *
   * `implementationClass` takes precedence over `implementation`, then the `className` child.
   *
   * @return the implementation class name, or an empty string if absent
   */
  def implementation: String = attributes.getOrElse("implementationClass", attributes.getOrElse("implementation", children.getOrElse("className", "")))
}

/**
 * Reads extension declarations, beans and registry defaults from descriptor XML.
 *
 * Nothing here loads a plugin, instantiates an extension or registers a service.
 */
object Descriptors {

  /**
   * Reads extension-point declarations from the platform and Scala plugin jars.
   *
   * Plugin jars are identified by file name because launchers may run from copies (sbt's `run` does).
   * Later declarations win; Scala plugin declarations take precedence over platform declarations.
   *
   * @param classpathJars jars visible to the running host
   * @param scalaPluginJars jars belonging to the Scala plugin
   * @return declarations keyed by fully qualified extension-point name
   */
  def extensionPoints(classpathJars: Seq[Path], scalaPluginJars: Seq[Path]): Map[String, EpDecl] = {
    val pluginJarNames = scalaPluginJars.map(_.getFileName.toString).toSet
    val platformJars = classpathJars.filterNot(jar => pluginJarNames.contains(jar.getFileName.toString))
    (declarations(platformJars, fromScala = false) ++ declarations(scalaPluginJars, fromScala = true)).toMap
  }

  /** Reads extension-point declarations from the descriptors in `jars`, retaining their source kind. */
  private def declarations(jars: Seq[Path], fromScala: Boolean): Seq[(String, EpDecl)] = {
    val factory = DocumentBuilderFactory.newInstance()
    val prefix = if (fromScala) "org.intellij.scala." else "com.intellij."
    for {
      jar <- jars
      (_, bytes) <- xmlEntries(jar) if new String(bytes, StandardCharsets.UTF_8).contains("<extensionPoint ")
      // Some jars ship XML that is not a descriptor (or not well-formed on its own); those declare no extension points.
      doc <- scala.util.Try(factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes))).toOption.toSeq
      nodes = doc.getElementsByTagName("extensionPoint")
      i <- 0 until nodes.getLength
      element = nodes.item(i).asInstanceOf[org.w3c.dom.Element]
      name = Option(element.getAttribute("qualifiedName")).filter(_.nonEmpty).getOrElse(prefix + element.getAttribute("name"))
      (className, kind) = Option(element.getAttribute("interface")).filter(_.nonEmpty)
        .map(_ -> ExtensionPoint.Kind.INTERFACE).getOrElse(element.getAttribute("beanClass") -> ExtensionPoint.Kind.BEAN_CLASS)
      if className.nonEmpty
    } yield name -> EpDecl(name, className, kind, element.getAttribute("dynamic") == "true", fromScala)
  }

  /**
   * Reads registry defaults required by Scala and Java PSI.
   *
   * Every listed descriptor must be present; a missing resource fails bootstrap.
   *
   * @param loader class loader providing the plugin and Java PSI descriptors
   * @return registry descriptors keyed by registry key
   */
  def scalaRegistryKeys(loader: ClassLoader): Map[String, RegistryKeyDescriptor] = {
    val descriptors = Seq("META-INF/scala-plugin-common.xml", "META-INF/sbt.xml", "META-INF/codeInsight.xml",
      "META-INF/scala-meta-impl.xml", "intellij.java.psi.impl.xml")
    descriptors.flatMap { path =>
      val input = loader.getResourceAsStream(path)
      if (input == null) throw new IllegalStateException(s"Scala plugin descriptor not on the classpath: $path")
      val doc = Using.resource(input)(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse)
      val nodes = doc.getElementsByTagName("registryKey")
      (0 until nodes.getLength).map(nodes.item(_).asInstanceOf[org.w3c.dom.Element]).map { e =>
        e.getAttribute("key") -> new RegistryKeyDescriptor(
          e.getAttribute("key"), e.getAttribute("description"), e.getAttribute("defaultValue"), false, false, "org.intellij.scala", null)
      }
    }.toMap
  }

  /**
   * Lists implementation classes registered for a Scala plugin extension point.
   *
   * Classes are returned in descriptor order without loading them; beans without an implementation are omitted.
   *
   * @param scalaPluginJars plugin jars in lookup order
   * @param epName fully qualified extension-point name
   * @return implementation class names in declaration order
   */
  def scalaExtensions(scalaPluginJars: Seq[Path], epName: String): Seq[String] =
    scalaExtensionBeans(scalaPluginJars, epName).map(_.implementation).filter(_.nonEmpty)

  /**
   * Reads the beans registered for a Scala plugin extension point.
   *
   * The result is data only; the caller chooses which implementations to register.
   *
   * @param scalaPluginJars plugin jars in lookup order
   * @param epName fully qualified extension-point name
   * @return matching descriptor beans in declaration order
   */
  def scalaExtensionBeans(scalaPluginJars: Seq[Path], epName: String): Seq[ExtensionBean] = {
    val factory = DocumentBuilderFactory.newInstance()
    for {
      jar <- scalaPluginJars
      (_, bytes) <- xmlEntries(jar) if new String(bytes, StandardCharsets.UTF_8).contains("<extensions")
      doc <- scala.util.Try(factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes))).toOption.toSeq
      blocks = doc.getElementsByTagName("extensions")
      b <- 0 until blocks.getLength
      block = blocks.item(b).asInstanceOf[org.w3c.dom.Element]
      namespace = block.getAttribute("defaultExtensionNs")
      children = block.getChildNodes
      i <- 0 until children.getLength
      e <- Option(children.item(i)).collect { case el: org.w3c.dom.Element => el }
      if s"$namespace.${e.getTagName}" == epName
    } yield {
      val attributes = (0 until e.getAttributes.getLength).map { index =>
        val attribute = e.getAttributes.item(index)
        attribute.getNodeName -> attribute.getNodeValue
      }.toMap
      val childText = (0 until e.getChildNodes.getLength).flatMap { index =>
        e.getChildNodes.item(index) match {
          case child: org.w3c.dom.Element => Some(child.getTagName -> child.getTextContent.trim)
          case _ => None
        }
      }.toMap
      ExtensionBean(attributes, childText)
    }
  }

  /** Reads XML entries from one jar while closing its zip file after the bytes have been collected. */
  private def xmlEntries(jar: Path): Seq[(String, Array[Byte])] =
    Using.resource(new ZipFile(jar.toFile)) { zip =>
      zip.entries().asScala.filter(_.getName.endsWith(".xml")).map { entry =>
        val bytes = Using.resource(zip.getInputStream(entry))(_.readAllBytes())
        entry.getName -> bytes
      }.toVector
    }
}
