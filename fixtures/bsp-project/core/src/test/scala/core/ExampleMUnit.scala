package core

class ExampleMUnit extends munit.FunSuite {
  test("adds numbers") { assertEquals(1 + 1, 2) }
  val dynamic = "dynamic"
  test(dynamic) { assert(true) }
  object Helper {
    def test(name: String)(body: => Unit): Unit = body
  }
  Helper.test("unrelated helper") { assert(true) }
}
