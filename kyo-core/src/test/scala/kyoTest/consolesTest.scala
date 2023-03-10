package kyoTest

import kyo.consoles._
import kyo.core._
import kyo.envs._
import kyo.ios._

class consolesTest extends KyoTest {

  "run" in new Context {
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(testConsole)(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "run implicit console" in new Context {
    given Console = testConsole
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "readln" in new Context {
    testConsole.readlns = List("readln")
    val io: String > IOs = Consoles.run(testConsole)(Consoles.readln)
    assert(IOs.run(io) == "readln")
  }
  "print" in new Context {
    IOs.run(Consoles.run(testConsole)(Consoles.print("print")))
    assert(testConsole.prints == List("print"))
  }
  "printErr" in new Context {
    IOs.run(Consoles.run(testConsole)(Consoles.printErr("printErr")))
    assert(testConsole.printErrs == List("printErr"))
  }
  "println" in new Context {
    IOs.run(Consoles.run(testConsole)(Consoles.println("println")))
    assert(testConsole.printlns == List("println"))
  }
  "printlnErr" in new Context {
    IOs.run(Consoles.run(testConsole)(Consoles.printlnErr("printlnErr")))
    assert(testConsole.printlnErrs == List("printlnErr"))
  }

  trait Context {
    object testConsole extends Console {
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
}
