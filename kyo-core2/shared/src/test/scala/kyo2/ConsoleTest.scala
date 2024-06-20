package kyo2

class ConsoleTest extends Test:

    case class Obj(a: String)
    val obj       = Obj("a")
    val pprintObj = pprint.apply(obj).toString

    "readln" in {
        val testConsole = new TestConsole
        testConsole.readlns = List("readln")
        val io: String < IO = Console.let(testConsole)(Console.readln)
        assert(IO.run(io).eval == "readln")
    }
    "print" in {
        val testConsole = new TestConsole
        IO.run(Console.let(testConsole)(Console.print("print"))).eval
        assert(testConsole.prints == List("print"))
    }
    "printErr" in {
        val testConsole = new TestConsole
        IO.run(Console.let(testConsole)(Console.printErr("printErr"))).eval
        assert(testConsole.printErrs == List("printErr"))
    }
    "println" in {
        val testConsole = new TestConsole
        IO.run(Console.let(testConsole)(Console.println("println"))).eval
        assert(testConsole.printlns == List("println"))
    }
    "printlnErr" in {
        val testConsole = new TestConsole
        IO.run(Console.let(testConsole)(Console.printlnErr("printlnErr"))).eval
        assert(testConsole.printlnErrs == List("printlnErr"))
    }

    class TestConsole extends Console.Service:
        var readlns     = List.empty[String]
        var prints      = List.empty[String]
        var printErrs   = List.empty[String]
        var printlns    = List.empty[String]
        var printlnErrs = List.empty[String]

        def readln(using Frame): String < IO =
            IO {
                val v = readlns.head
                readlns = readlns.tail
                v
            }
        def print(s: String)(using Frame): Unit < IO =
            IO {
                prints ::= s
            }
        def printErr(s: String)(using Frame): Unit < IO =
            IO {
                printErrs ::= s
            }
        def println(s: String)(using Frame): Unit < IO =
            IO {
                printlns ::= s
            }
        def printlnErr(s: String)(using Frame): Unit < IO =
            IO {
                printlnErrs ::= s
            }
    end TestConsole

end ConsoleTest
