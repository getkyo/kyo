package kyo

import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream

class ConsoleTest extends kyo.test.Test[Any]:

    case class Obj(a: String)
    val obj       = Obj("a")
    val pprintObj = pprint.apply(obj).toString

    "readLine" in {
        Console.withIn(List("readln")) {
            Console.readLine.map { result =>
                assert(result == "readln")
            }
        }
    }
    "print string" in {
        Console.withOut(Console.print("print")).map { (out, _) =>
            assert(out.stdOut == "print")
        }
    }
    "printErr" in {
        Console.withOut(Console.printErr("printErr")).map { (out, _) =>
            assert(out.stdErr == "printErr")
        }
    }
    "println" in {
        Console.withOut(Console.printLine("print")).map { (out, _) =>
            assert(out.stdOut == "print\n")
        }
    }
    "printlnErr" in {
        Console.withOut(Console.printLineErr("print")).map { (out, _) =>
            assert(out.stdErr == "print\n")
        }
    }

    "checkErrors on out channel" in {
        val buffer = new PrintStream(new OutputStream:
            override def write(b: Int): Unit = throw IOException())
        scala.Console.withOut(buffer) {
            import AllowUnsafe.embrace.danger
            val (r1, r2) =
                Sync.Unsafe.evalOrThrow {
                    for
                        r1 <- Abort.run(Console.print("test"))
                        r2 <- Abort.run(Console.checkErrors)
                    yield (r1, r2)
                }
            assert(r1.isSuccess && r2.isSuccess)
            assert(r2.getOrThrow)
        }
    }

    "checkErrors on err channel" in {
        val buffer = new PrintStream(new OutputStream:
            override def write(b: Int): Unit = throw IOException())
        scala.Console.withErr(buffer) {
            import AllowUnsafe.embrace.danger
            val (r1, r2) =
                Sync.Unsafe.evalOrThrow {
                    for
                        r1 <- Abort.run(Console.printErr("test"))
                        r2 <- Abort.run(Console.checkErrors)
                    yield (r1, r2)
                }
            assert(r1.isSuccess && r2.isSuccess)
            assert(r2.getOrThrow)
        }
    }

    "live" in {
        val output = new StringBuilder
        val error  = new StringBuilder
        scala.Console.withOut(new java.io.PrintStream(new java.io.OutputStream:
            override def write(b: Int): Unit = output.append(b.toChar))) {
            scala.Console.withErr(new java.io.PrintStream(new java.io.OutputStream:
                override def write(b: Int): Unit = error.append(b.toChar))) {
                import AllowUnsafe.embrace.danger
                val (r1, r2, r3, r4) =
                    Sync.Unsafe.evalOrThrow {
                        for
                            r1 <- Abort.run(Console.print("test"))
                            r2 <- Abort.run(Console.printLine(" message"))
                            r3 <- Abort.run(Console.printErr("error"))
                            r4 <- Abort.run(Console.printLineErr(" message"))
                        yield (r1, r2, r3, r4)
                    }
                assert(r1.isSuccess && r2.isSuccess && r3.isSuccess && r4.isSuccess)
                assert(output.toString.replace("\r\n", "\n") == "test message\n")
                assert(error.toString.replace("\r\n", "\n") == "error message\n")
            }
        }
    }

    "unsafe" - {
        import AllowUnsafe.embrace.danger

        "should read line correctly" in {
            val testUnsafe = new TestUnsafeConsole("test input")
            assert(testUnsafe.readLine() == Result.succeed("test input"))
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

        "should check errors correctly" in {
            val testUnsafe = new TestUnsafeConsole(error = true)
            assert(testUnsafe.checkErrors)
        }

        "should convert to safe Console" in {
            val testUnsafe           = new TestUnsafeConsole()
            val safeConsole: Console = testUnsafe.safe
            discard(safeConsole)
            succeed("Unsafe.safe returns a safe Console wrapper (verified by the Console ascription)")
        }
    }

    // A read that throws must arrive as a Result, never escape the handler. On Node it used to escape: `scala.Console.in` is null there,
    // and the Scala.js linker reports the dereference as UndefinedBehaviorError, an Error rather than an Exception, so the handler let it
    // past and no caller could turn it into a clean shutdown. The Node read no longer throws, and this pins the contract that carried the
    // failure: whatever readLine throws, `Abort.run` answers with it.
    "a throwing readLine arrives as a Panic rather than escaping the handler" in {
        val boom = new RuntimeException("stdin exploded")
        val throwing = new Console.Unsafe:
            def readLine()(using AllowUnsafe)              = throw boom
            def print(s: String)(using AllowUnsafe)        = ()
            def printErr(s: String)(using AllowUnsafe)     = ()
            def printLine(s: String)(using AllowUnsafe)    = ()
            def printLineErr(s: String)(using AllowUnsafe) = ()
            def checkErrors(using AllowUnsafe): Boolean    = false
            def flush()(using AllowUnsafe)                 = ()
        Console.let(Console(throwing)) {
            Abort.run[Any](Console.readLine).map {
                case Result.Panic(cause) => assert(cause eq boom)
                case other               => fail(s"expected a Panic carrying the thrown error, got $other")
            }
        }
    }

    // The other half of the same contract: a read that FAILS rather than throws stays inside the declared Abort[IOException].
    "a failing readLine arrives as a typed Failure" in {
        val eof = new java.io.EOFException("no more input")
        val failing = new Console.Unsafe:
            def readLine()(using AllowUnsafe)              = Result.fail(eof)
            def print(s: String)(using AllowUnsafe)        = ()
            def printErr(s: String)(using AllowUnsafe)     = ()
            def printLine(s: String)(using AllowUnsafe)    = ()
            def printLineErr(s: String)(using AllowUnsafe) = ()
            def checkErrors(using AllowUnsafe): Boolean    = false
            def flush()(using AllowUnsafe)                 = ()
        Console.let(Console(failing)) {
            Abort.run[IOException](Console.readLine).map {
                case Result.Failure(cause) => assert(cause eq eof)
                case other                 => fail(s"expected the EOF failure, got $other")
            }
        }
    }

    class TestUnsafeConsole(input: String = "", error: Boolean = false) extends Console.Unsafe:
        var readlnInput = input
        var prints      = List.empty[String]
        var printErrs   = List.empty[String]
        var printlns    = List.empty[String]
        var printlnErrs = List.empty[String]

        def readLine()(using AllowUnsafe) =
            Result.succeed(readlnInput)
        def print(s: String)(using AllowUnsafe) =
            prints = s :: prints
            ()
        def printErr(s: String)(using AllowUnsafe) =
            printErrs = s :: printErrs
            ()
        def printLine(s: String)(using AllowUnsafe) =
            printlns = s :: printlns
            ()
        def printLineErr(s: String)(using AllowUnsafe) =
            printlnErrs = s :: printlnErrs
            ()
        def checkErrors(using AllowUnsafe): Boolean = error
        def flush()(using AllowUnsafe)              = ()
    end TestUnsafeConsole

end ConsoleTest
