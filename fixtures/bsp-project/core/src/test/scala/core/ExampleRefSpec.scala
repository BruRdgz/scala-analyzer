package core

class ExampleRefSpec extends org.scalatest.refspec.RefSpec {
  def `adds numbers`(): Unit = { assert(1 + 1 == 2) }
}
