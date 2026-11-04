package core

class ExampleWordSpec extends org.scalatest.wordspec.AnyWordSpec {
  "a calculator" should {
    "add numbers" in { assert(1 + 1 == 2) }
    "subtract numbers" in { assert(2 - 1 == 1) }
  }
}
