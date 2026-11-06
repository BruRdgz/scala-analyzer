// The plugin keeps its MUnit finder package-private; living in its package keeps the call compile-checked.
package org.jetbrains.plugins.scala.testingSupport

import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScTypeDefinition
import org.jetbrains.plugins.scala.testingSupport.test.munit.MUnitTestLocationsFinder

/** The MUnit tests the plugin's run markers anchor to; the public MUnit producer needs the JUnit plugin. */
object MUnitLocations {
  /**
   * Finds the test elements of an MUnit suite using the plugin's own locations finder.
   *
   * @param suite suite class to scan
   * @return the elements the plugin anchors the suite's tests to; empty when its finder reports none
   */
  def in(suite: ScTypeDefinition): Seq[PsiElement] =
    MUnitTestLocationsFinder.calculateTestLocations(suite).getOrElse(Seq.empty)
}
