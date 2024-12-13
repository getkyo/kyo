package kyo

class ConsoleTest extends Test:

    case class Obj(a: String)
    val obj       = Obj("a")
    val pprintObj = pprint.apply(obj).toString

    "readLine" in run {
        Console.withIn(List("readln")) {
            Console.readLine.map { result =>
                assert(result == "readln")
            }
        }
    }
    "print string" in run {
        Console.withOut(Console.print("print")).map { (out, _) =>
            assert(out.stdOut == "print")
        }
    }
    "printErr" in run {
        Console.withOut(Console.printErr("printErr")).map { (out, _) =>
            assert(out.stdErr == "printErr")
        }
    }
    "println" in run {
        Console.withOut(Console.printLine("print")).map { (out, _) =>
            assert(out.stdOut == "print\n")
        }
    }
    "printlnErr" in run {
        Console.withOut(Console.printLineErr("print")).map { (out, _) =>
            assert(out.stdErr == "print\n")
        }
    }

    "live" in {
        val output = new StringBuilder
        val error  = new StringBuilder
        scala.Console.withOut(new java.io.PrintStream(new java.io.OutputStream:
            override def write(b: Int): Unit = output.append(b.toChar)
        )) {
            scala.Console.withErr(new java.io.PrintStream(new java.io.OutputStream:
                override def write(b: Int): Unit = error.append(b.toChar)
            )) {
                import AllowUnsafe.embrace.danger
                val (r1, r2, r3, r4) =
                    IO.Unsafe.evalOrThrow {
                        for
                            r1 <- Abort.run(Console.print("test"))
                            r2 <- Abort.run(Console.printLine(" message"))
                            r3 <- Abort.run(Console.printErr("error"))
                            r4 <- Abort.run(Console.printLineErr(" message"))
                        yield (r1, r2, r3, r4)
                    }
                assert(r1.isSuccess && r2.isSuccess && r3.isSuccess && r4.isSuccess)
                assert(output.toString == "test message\n")
                assert(error.toString == "error message\n")
            }
        }
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        "should read line correctly" in {
            val testUnsafe = new TestUnsafeConsole("test input")
            assert(testUnsafe.readLine() == Result.success("test input"))
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
            testUnsafe.printLine("test line")
            assert(testUnsafe.printlns.head == "test line")
        }

        "should println error correctly" in {
            val testUnsafe = new TestUnsafeConsole()
            testUnsafe.printLineErr("test error line")
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

        def readLine()(using AllowUnsafe) =
            Result.success(readlnInput)
        def print(s: String)(using AllowUnsafe) =
            prints = s :: prints
            Result.unit
        def printErr(s: String)(using AllowUnsafe) =
            printErrs = s :: printErrs
            Result.unit
        def printLine(s: String)(using AllowUnsafe) =
            printlns = s :: printlns
            Result.unit
        def printLineErr(s: String)(using AllowUnsafe) =
            printlnErrs = s :: printlnErrs
            Result.unit
        def flush()(using AllowUnsafe) = Result.unit
    end TestUnsafeConsole

end ConsoleTest
