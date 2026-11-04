package core

class ExamplePropSpec extends org.scalatest.propspec.AnyPropSpec {
  property("adds numbers") { assert(1 + 1 == 2) }
}
