// The plugin keeps the checks its Extract Method runs before its dialog package-private; living in its package keeps
// the call compile-checked.
package org.jetbrains.plugins.scala.lang.refactoring

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.ScalaBundle
import org.jetbrains.plugins.scala.lang.refactoring.util.ScalaRefactoringUtil

/** The plugin's checks of the elements that Extract Method is asked to extract. */
object ExtractMethodChecks {
  /**
   * Checks the elements as the plugin's Extract Method does before it goes on: statements that it can extract, in
   * which no definition used outside them is.
   *
   * The plugin shows the reason as an error hint, which in its unit-test mode is a
   * `RefactoringErrorHintException` that this does not catch.
   *
   * @param elements the elements the selection covers
   * @param project project of the selection's file
   * @param editor editor whose selection the elements are
   * @return `true` when the plugin refuses the elements and has shown why
   */
  def refuses(elements: Seq[PsiElement])(using project: Project, editor: Editor): Boolean =
    ScalaRefactoringUtil.showNotPossibleWarnings(elements, ScalaBundle.message("extract.method.title"))
}
