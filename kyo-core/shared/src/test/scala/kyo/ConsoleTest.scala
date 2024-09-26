package kyo

class ConsoleTest extends Test:

    case class Obj(a: String)
    val obj       = Obj("a")
    val pprintObj = pprint.apply(obj).toString

    "readln" in run {
        val testConsole = new TestConsole
        testConsole.readlns = List("readln")
        Console.let(testConsole)(Console.readln).map { result =>
            assert(result == "readln")
        }
    }
    "print" in run {
        val testConsole = new TestConsole
        Console.let(testConsole)(Console.print("print")).andThen {
            assert(testConsole.prints == List("print"))
        }
    }
    "printErr" in run {
        val testConsole = new TestConsole
        Console.let(testConsole)(Console.printErr("printErr")).andThen {
            assert(testConsole.printErrs == List("printErr"))
        }
    }
    "println" in run {
        val testConsole = new TestConsole
        Console.let(testConsole)(Console.println("println")).andThen {
            assert(testConsole.printlns == List("println"))
        }
    }
    "printlnErr" in run {
        val testConsole = new TestConsole
        Console.let(testConsole)(Console.printlnErr("printlnErr")).andThen {
            assert(testConsole.printlnErrs == List("printlnErr"))
        }
    }

    class TestConsole extends Console:
        var readlns     = List.empty[String]
        var prints      = List.empty[String]
        var printErrs   = List.empty[String]
        var printlns    = List.empty[String]
        var printlnErrs = List.empty[String]

        def unsafe = ???

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

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        "should read line correctly" in {
            val testUnsafe = new TestUnsafeConsole("test input")
            assert(testUnsafe.readln() == "test input")
        }

        "should print correctly" in {
            val testUnsafe = new TestUnsafeConsole()
            testUnsafe.print("test output")
            assert(testUnsafe.prints.head == "test output")
        }

        "should print error correctly" in {
            val testUnsafe = new TestUnsafeConsole()
            testUnsafe.printErr("test error")
            assert(testUnsafe.printErrs.head == "test error")
        }

        "should println correctly" in {
            val testUnsafe = new TestUnsafeConsole()
            testUnsafe.println("test line")
            assert(testUnsafe.printlns.head == "test line")
        }

        "should println error correctly" in {
            val testUnsafe = new TestUnsafeConsole()
            testUnsafe.printlnErr("test error line")
            assert(testUnsafe.printlnErrs.head == "test error line")
        }

        "should convert to safe Console" in {
            val testUnsafe  = new TestUnsafeConsole()
            val safeConsole = testUnsafe.safe
            assert(safeConsole.isInstanceOf[Console])
        }
    }

    class TestUnsafeConsole(input: String = "") extends Console.Unsafe:
        var readlnInput = input
        var prints      = List.empty[String]
        var printErrs   = List.empty[String]
        var printlns    = List.empty[String]
        var printlnErrs = List.empty[String]

        def readln()(using AllowUnsafe): String =
            readlnInput
        def print(s: String)(using AllowUnsafe): Unit =
            prints = s :: prints
        def printErr(s: String)(using AllowUnsafe): Unit =
            printErrs = s :: printErrs
        def println(s: String)(using AllowUnsafe): Unit =
            printlns = s :: printlns
        def printlnErr(s: String)(using AllowUnsafe): Unit =
            printlnErrs = s :: printlnErrs
    end TestUnsafeConsole

end ConsoleTest
