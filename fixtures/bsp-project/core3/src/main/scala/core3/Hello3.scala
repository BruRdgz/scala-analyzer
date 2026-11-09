package core3

@main def hello3(): Unit = println("Hello from hello3")

@main def fail3(): Unit = sys.exit(3)

object Entry3 extends App:
  println("Hello from Entry3")
