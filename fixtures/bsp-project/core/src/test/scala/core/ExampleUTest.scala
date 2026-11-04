package core

import utest._

object ExampleUTest extends TestSuite {
  val tests = Tests {
    test("outer") {
      test("inner") { assert(1 + 1 == 2) }
    }
  }
}
