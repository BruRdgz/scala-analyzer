package core

class FailingSuite extends org.scalatest.funsuite.AnyFunSuite {
  test("passes") { assert(true) }
  test("fails") { fail("deliberate failure") }
  ignore("ignored") { fail("must not run") }
}
