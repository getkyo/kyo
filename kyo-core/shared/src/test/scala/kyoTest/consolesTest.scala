package kyoTest

import kyo.*

class consolesTest extends KyoTest:

    case class Obj(a: String)
    val obj       = Obj("a")
    val pprintObj = pprint.apply(obj).toString

    "readln" in IOs.run {
        val testConsole = new TestConsole
        testConsole.readlns = List("readln")
        val io: String < IOs = Consoles.let(testConsole)(Consoles.readln)
        assert(IOs.run(io) == "readln")
    }
    "print" in IOs.run {
        val testConsole = new TestConsole
        IOs.run(Consoles.let(testConsole)(Consoles.print("print")))
        assert(testConsole.prints == List("print"))
    }
    "printErr" in IOs.run {
        val testConsole = new TestConsole
        IOs.run(Consoles.let(testConsole)(Consoles.printErr("printErr")))
        assert(testConsole.printErrs == List("printErr"))
    }
    "println" in IOs.run {
        val testConsole = new TestConsole
        IOs.run(Consoles.let(testConsole)(Consoles.println("println")))
        assert(testConsole.printlns == List("println"))
    }
    "printlnErr" in IOs.run {
        val testConsole = new TestConsole
        IOs.run(Consoles.let(testConsole)(Consoles.printlnErr("printlnErr")))
        assert(testConsole.printlnErrs == List("printlnErr"))
    }

    class TestConsole extends Console:
        var readlns     = List.empty[String]
        var prints      = List.empty[String]
        var printErrs   = List.empty[String]
        var printlns    = List.empty[String]
        var printlnErrs = List.empty[String]

        def readln: String < IOs =
            IOs {
                val v = readlns.head
                readlns = readlns.tail
                v
            }
        def print(s: String): Unit < IOs =
            IOs {
                prints ::= s
            }
        def printErr(s: String): Unit < IOs =
            IOs {
                printErrs ::= s
            }
        def println(s: String): Unit < IOs =
            IOs {
                printlns ::= s
            }
        def printlnErr(s: String): Unit < IOs =
            IOs {
                printlnErrs ::= s
            }
    end TestConsole
end consolesTest
