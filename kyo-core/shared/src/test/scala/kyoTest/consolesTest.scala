package kyoTest

import kyo.consoles._
import kyo._
import kyo.envs._
import kyo.ios._

class consolesTest extends KyoTest {

  "run" in run {
    val testConsole = new TestConsole
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(testConsole)(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "run implicit console" in run {
    val testConsole               = new TestConsole
    implicit def console: Console = testConsole
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "readln" in run {
    val testConsole = new TestConsole
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(testConsole)(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "print" in run {
    val testConsole = new TestConsole
    IOs.run(Consoles.run(testConsole)(Consoles.print("print")))
    assert(testConsole.prints == List("print"))
  }
  "printErr" in run {
    val testConsole = new TestConsole
    IOs.run(Consoles.run(testConsole)(Consoles.printErr("printErr")))
    assert(testConsole.printErrs == List("printErr"))
  }
  "println" in run {
    val testConsole = new TestConsole
    IOs.run(Consoles.run(testConsole)(Consoles.println("println")))
    assert(testConsole.printlns == List("println"))
  }
  "printlnErr" in run {
    val testConsole = new TestConsole
    IOs.run(Consoles.run(testConsole)(Consoles.printlnErr("printlnErr")))
    assert(testConsole.printlnErrs == List("printlnErr"))
  }

  class TestConsole extends Console {
    var readlns     = List.empty[String]
    var prints      = List.empty[String]
    var printErrs   = List.empty[String]
    var printlns    = List.empty[String]
    var printlnErrs = List.empty[String]

    def readln: String > IOs =
      IOs {
        val v = readlns.head
        readlns = readlns.tail
        v
      }
    def print(s: => String): Unit > IOs =
      IOs {
        prints ::= s
      }
    def printErr(s: => String): Unit > IOs =
      IOs {
        printErrs ::= s
      }
    def println(s: => String): Unit > IOs =
      IOs {
        printlns ::= s
      }
    def printlnErr(s: => String): Unit > IOs =
      IOs {
        printlnErrs ::= s
      }
  }
}
