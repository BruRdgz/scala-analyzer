package scalaanalyzer.lsp

import scalaanalyzer.language.{QuickDoc}

/** The plugin's documentation HTML as Markdown: fragments shaped like the generator's output, and the Markdown expected of them. */
class QuickDocTest extends munit.FunSuite {
  private def doc(content: String = "", sections: String = ""): String =
    s"""<html><head><style>p { margin: 0; }</style></head><body><div class="bottom"></div>
       |<div class='definition'><pre>def signature</pre></div><div class='content'>$content</div>
       |<table class='sections'>$sections</table></body></html>""".stripMargin

  private val box = """<html><head><style>p { margin: 0; }</style></head><body><div class="bottom"><icon src="AllIcons.Nodes.Package"/>&nbsp;<a href="psi_element://spike"><code>spike</code></a></div>
<div class='definition'><pre><span style="">class</span> <span style="">Box</span>[<span style="">A</span>](<span style="">val </span>v: <span style="">A</span>)</pre></div><div class='content'>A box holding one value.
<p>Use <a href="psi_element://Box.map"><code>Box.map</code></a> to transform it, or see <a href="psi_element://scala.Option"><code>scala.Option</code></a>. Some <tt>inline code</tt>, <b>bold</b>, <i>italic</i>.</p>
<pre><code>val b = new Box(1)
b.map(_ + 1)</code></pre>
<ul><li>first item
</li><li>second item

</li></ul></div><table class='sections'><tr><td valign='top' class='section'><p>Params:</td><td valign='top'><code>v</code> &ndash; the value</td><tr><td valign='top' class='section'><p>Type parameters:</td><td valign='top'><code>A</code> &ndash; the value's type</td><tr><td valign='top' class='section'><p>See also:</td><td valign='top'><a href="https://example.org">the docs</a></td></table></body></html>"""

  private val map = """<html><head><style>p { margin: 0; }</style></head><body><div class="bottom"><icon src="org.jetbrains.plugins.scala.icons.Icons.CLASS"/>&nbsp;<a href="psi_element://spike.Box"><code>spike.Box</code></a></div>
<div class='definition'><pre><span style="">def</span> <span style="">map</span>[<span style="">B</span>](f: <span style=""><a href="psi_element://scala.Function1"><code>A =&gt; B</code></a></span>): <span style="">Box[B]</span></pre></div><div class='content'>Applies <tt>f</tt> to the value.
</div><table class='sections'><tr><td valign='top' class='section'><p>Params:</td><td valign='top'><code>f</code> &ndash; the function</td><tr><td valign='top' class='section'><p>Returns:</td><td valign='top'>a new box
</td><tr><td valign='top' class='section'><p>Throws:</td><td valign='top'><code>IllegalStateException</code> &ndash; never</td></table></body></html>"""

  private val two = """<html><head></head><body><div class='content'>Two.
</div><table class='sections'><tr><td valign='top' class='section'><p>Params:</td><td valign='top'><code>a</code> &ndash; first<p><code>b</code> &ndash; second <tt>x</tt> too</td><tr><td valign='top' class='section'><p>See also:</td><td valign='top'><a href="https://a.org">A</a></td><tr><td valign='top' class='section'><p>See also:</td><td valign='top'><a href="https://b.org">B</a></td></table></body></html>"""

  private val conversions = Seq(
    "a class's doc" -> (box,
      """**Defined in:** `spike`
        |
        |A box holding one value.
        |
        |Use `Box.map` to transform it, or see `scala.Option`. Some `inline code`, **bold**, *italic*.
        |
        |```scala
        |val b = new Box(1)
        |b.map(_ + 1)
        |```
        |
        |- first item
        |- second item
        |
        |### Parameters
        |
        |- `v` – the value
        |
        |### Type parameters
        |
        |- `A` – the value's type
        |
        |### See also
        |
        |- [the docs](https://example.org)""".stripMargin),
    "a method's doc" -> (map,
      """**Defined in:** `spike.Box`
        |
        |Applies `f` to the value.
        |
        |### Parameters
        |
        |- `f` – the function
        |
        |### Returns
        |
        |a new box
        |
        |### Throws
        |
        |- `IllegalStateException` – never""".stripMargin),
    "a section with several entries" -> (two,
      """Two.
        |
        |### Parameters
        |
        |- `a` – first
        |- `b` – second `x` too
        |
        |### See also
        |
        |- [A](https://a.org)
        |- [B](https://b.org)""".stripMargin),
    "a code span with a backtick" -> (doc("<tt>a`b</tt> and <tt>``</tt>"), "``a`b`` and ``` `` ```"),
    "a code block with indentation and blank lines" ->
      (doc("<pre>if (x) {\n  y\n\n}\n</pre>"), "```scala\nif (x) {\n  y\n\n}\n```"),
    "a Markdown code block in another language" ->
      (doc("<pre><code class=\"language-java\">int x = 1;</code></pre>"), "```java\nint x = 1;\n```"),
    "an ordered list" -> (doc("<ol><li>one</li><li>two</li></ol>after"), "1. one\n2. two\n\nafter"),
    "a heading and a line break" -> (doc("<h2>Usage</h2>a<br>b"), "## Usage\n\na\\\nb"),
    "entities" -> (doc("a&nbsp;&nbsp;b &ndash; &lt;c&gt; &amp; d"), "a b – \\<c\\> & d"),
    "markdown characters in prose" -> (doc("a_b *c* [d] #e \\f `g`"), "a\\_b \\*c\\* \\[d\\] \\#e \\\\f \\`g\\`"),
    "markdown characters in code" -> (doc("<tt>a_b *c*</tt><pre>[d] #e</pre>"), "`a_b *c*`\n\n```scala\n[d] #e\n```"),
    "whitespace" -> (doc("  a \n\n   b  <p>  c</p><p></p><p>d</p> "), "a b\n\nc\n\nd"),
    "a table inside the content" ->
      (doc("before<table><tr><td>cell</td></tr></table><div>inner</div><p>after</p>") + "<div>outside</div>", "before\n\n|  |\n| --- |\n| cell |\n\ninner\n\nafter"),
    "a table with headers and escaped cell separators" ->
      (doc("<table><tr><th>Name</th><th>Value</th></tr><tr><td><code>a|b</code></td><td>first<br>second</td></tr></table>"),
        "| Name | Value |\n| --- | --- |\n| `a\\|b` | first<br>second |"),
    "nested ordered and unordered lists" ->
      (doc("<ol><li>one<ul><li>inner</li></ul></li><li>two</li></ol>"), "1. one\n   - inner\n2. two"),
    "a literal code fence" ->
      (doc("<pre>```md\nexample\n```</pre>"), "````scala\n```md\nexample\n```\n````"),
    "a table within a documentation section" ->
      (doc(sections = "<tr><td class='section'>Returns:</td><td>Values:<table><tr><th>Key</th><th>Value</th></tr><tr><td>a</td><td>1</td></tr></table>After.</td></tr>"),
        "### Returns\n\nValues:\n\n| Key | Value |\n| --- | --- |\n| a | 1 |\n\nAfter."),
    "paragraphs and code inside a list item" ->
      (doc("<ul><li>Start<p><b>More.</b></p><p><code>value</code> here.</p><pre>x\ny</pre></li><li>Next</li></ul>"),
        "- Start\n\n  **More.**\n\n  `value` here.\n\n  ```scala\n  x\n  y\n  ```\n\n- Next"),
    "paragraphs within a return section" ->
      (doc(sections = "<tr><td class='section'>Returns:</td><td><p>A result.</p><p>May be empty.</p></td></tr>"),
        "### Returns\n\nA result.\n\nMay be empty."),
    "external link destinations with Markdown delimiters" ->
      (doc("<a href='https://example.org/f(a)'>reference</a> and <a href='psi_element://A'>A</a>"),
        "[reference](https://example.org/f%28a%29) and A"),
    "an unknown tag" -> (doc("<span style=\"x\">text</span> <icon src=\"y\"/>more"), "text more"),
    "a section without content" -> (doc(sections = "<tr><td class='section'><p>Returns:</td><td>x</td>"), "### Returns\n\nx"),
  )

  conversions.foreach { case (name, (html, markdown)) =>
    test(s"converts $name") {
      assertEquals(QuickDoc.toMarkdown(html), Some(markdown))
    }
  }

  test("Java documentation defaults code blocks to Java while retaining explicit languages") {
    assertEquals(QuickDoc.toMarkdown(doc("<pre>int value = 1;</pre>"), "java"),
      Some("```java\nint value = 1;\n```"))
    assertEquals(QuickDoc.toMarkdown(doc("<pre><code class=\"language-scala\">val value = 1</code></pre>"), "java"),
      Some("```scala\nval value = 1\n```"))
  }

  test("has no Markdown for an empty doc") {
    assertEquals(QuickDoc.toMarkdown(doc()), None)
    assertEquals(QuickDoc.toMarkdown(doc(content = "\n", sections = "")), None)
  }
}
