package core

class ExampleSpecs2 extends org.specs2.mutable.Specification {
  "a calculator" should {
    "add numbers" in { (1 + 1) mustEqual 2 }
    "subtract numbers" in { (2 - 1) mustEqual 1 }
  }
}
