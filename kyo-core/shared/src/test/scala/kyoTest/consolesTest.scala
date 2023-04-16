package kyoTest

import kyo.consoles._
import kyo.core._
import kyo.envs._
import kyo.ios._

class consolesTest extends KyoTest {

  "run" in run {
    testConsole.clear()
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(testConsole)(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "run implicit console" in run {
    testConsole.clear()
    given Console = testConsole
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "readln" in run {
    testConsole.clear()
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(testConsole)(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "print" in run {
    testConsole.clear()
    IOs.run(Consoles.run(testConsole)(Consoles.print("print")))
    assert(testConsole.prints == List("print"))
  }
  "printErr" in run {
    testConsole.clear()
    IOs.run(Consoles.run(testConsole)(Consoles.printErr("printErr")))
    assert(testConsole.printErrs == List("printErr"))
  }
  "println" in run {
    testConsole.clear()
    IOs.run(Consoles.run(testConsole)(Consoles.println("println")))
    assert(testConsole.printlns == List("println"))
  }
  "printlnErr" in run {
    testConsole.clear()
    IOs.run(Consoles.run(testConsole)(Consoles.printlnErr("printlnErr")))
    assert(testConsole.printlnErrs == List("printlnErr"))
  }

  object testConsole extends Console {
    var readlns     = List.empty[String]
    var prints      = List.empty[String]
    var printErrs   = List.empty[String]
    var printlns    = List.empty[String]
    var printlnErrs = List.empty[String]

    def clear() =
      readlns = List.empty[String]
      prints = List.empty[String]
      printErrs = List.empty[String]
      printlns = List.empty[String]
      printlnErrs = List.empty[String]

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
