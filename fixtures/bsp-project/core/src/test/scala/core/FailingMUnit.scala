package core

class FailingMUnit extends munit.FunSuite {
  test("passes") { assert(true) }
  test("fails") { fail("deliberate failure") }
  test("ignored".ignore) { fail("must not run") }
}
