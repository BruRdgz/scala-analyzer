package core

class ExampleFreeSpec extends org.scalatest.freespec.AnyFreeSpec {
  "a calculator" - {
    "adds numbers" in { assert(1 + 1 == 2) }
  }
}
