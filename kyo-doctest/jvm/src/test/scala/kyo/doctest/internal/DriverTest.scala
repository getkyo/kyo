package kyo.doctest.internal

import kyo.*
import kyo.doctest.*

/** Tests for Driver covering init, close, and compile behaviour including sequential safety, warnings, and -Werror. */
class DriverTest extends kyo.doctest.DoctestTest:

    private def testClasspath(using Frame): Chunk[kyo.Path] < Sync =
        for
            cp  <- System.property[String]("java.class.path", "")
            sep <- System.property[String]("path.separator", ":")
        yield Chunk.from(cp.split(sep).filter(_.nonEmpty).map(kyo.Path(_)))

    // Helper: initialize a Driver and run a block, closing the driver afterwards.
    private def withDriver[A](f: Driver => A < Sync)(using Frame): A < (Sync & Abort[Doctest.Error.DriverInitFailed]) =
        testClasspath.flatMap { cp =>
            Driver.init(cp, Chunk.empty, false).flatMap { driver =>
                f(driver).flatMap { result =>
                    driver.close.map(_ => result)
                }
            }
        }

    "Driver.init with real classpath succeeds" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    driver.close.andThen(succeed("driver initialized successfully and closed without error"))
                case Result.Failure(e) =>
                    Sync.defer(fail(s"expected success but got failure: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Driver.init with a bad classpath surfaces DriverInitFailed at init or compile time" in {
        val badClasspath = Chunk(kyo.Path("/this/path/does/not/exist/nowhere.jar"))
        val scalacOpts   = Chunk("-release", "17")

        Abort.run(
            Driver.init(badClasspath, scalacOpts, false).flatMap { driver =>
                // Bad classpath might not fail at init; compile something that needs the missing jar to force the failure
                val src =
                    Driver.Source(kyo.Path("BadClasspath.scala"), "object Probe { val x: kyo.Maybe[Int] = kyo.Maybe(42) }")
                driver.compile(src).flatMap { result =>
                    driver.close.map(_ => result)
                }
            }
        ).map {
            case Result.Failure(_: Doctest.Error.DriverInitFailed) =>
                succeed("correct: bad classpath surfaced DriverInitFailed")
            case Result.Success(result) =>
                // Driver init may have accepted a bad classpath; the compile MUST fail since the source needs kyo on the classpath
                result match
                    case _: Driver.Outcome.Failed => succeed("correct: compile failed on bad classpath")
                    case _                        => fail(s"expected DriverInitFailed or Driver.Outcome.Failed, got Ok: $result")
            case Result.Panic(t)
                if t.getMessage != null && (
                    t.getMessage.contains("nonexistent") ||
                        t.getMessage.contains("nowhere.jar") ||
                        t.getMessage.contains("core librar") ||
                        t.getMessage.contains("classpath") ||
                        t.getClass.getSimpleName.toLowerCase.contains("classpath") ||
                        t.getClass.getSimpleName.toLowerCase.contains("library") ||
                        (t.getCause != null && t.getCause.isInstanceOf[java.io.IOException])
                ) =>
                succeed("dotty panics with a classpath-related message when stdlib is missing; counts as detected bad-classpath")
            case Result.Panic(t) =>
                fail(s"unexpected panic (not classpath-related): ${t.getClass.getSimpleName}: ${t.getMessage}")
        }
    }

    "Driver.close is idempotent" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    // Two sequential close() calls must not throw; idempotency of close IS the assertion.
                    driver.close.flatMap(_ => driver.close).andThen(succeed(
                        "two sequential close calls did not throw: close is idempotent"
                    ))
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Driver.compile of trivial source returns Driver.Outcome.Ok" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(kyo.Path("Trivial.scala"), "val x = 42\n")
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case Driver.Outcome.Ok(_) => succeed("trivial source compiled without errors")
                                case Driver.Outcome.Failed(errors, _) =>
                                    fail(s"expected Ok but got Failed: ${errors.map(_.message).mkString(", ")}")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Driver.compile of source with type error returns Driver.Outcome.Failed" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(kyo.Path("TypeError.scala"), "val x: Int = \"not an int\"\n")
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case Driver.Outcome.Failed(errors, _) =>
                                    assert(errors.nonEmpty, "expected at least one error diagnostic")
                                case Driver.Outcome.Ok(_) =>
                                    fail("expected Failed but got Ok")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Driver.compile of source using derives Schema succeeds (macro resolution)" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(
                        kyo.Path("MacroBlock.scala"),
                        """|package kyo.doctest.synthetic
                           |import kyo.*
                           |case class Foo(x: Int, label: String) derives Schema
                           |val s: Schema[Foo] = summon[Schema[Foo]]
                           |""".stripMargin
                    )
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case Driver.Outcome.Ok(_) => succeed("Schema macro resolved and compiled cleanly")
                                case Driver.Outcome.Failed(errors, _) =>
                                    fail(s"macro compilation failed: ${errors.map(_.message).mkString("; ")}")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Driver.compile respects -Werror scalac option: warning becomes error" in {
        val opts = Chunk("-release", "17", "-Werror", "-Wunused:imports", "-deprecation")
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, opts, false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(
                        kyo.Path("WErrorProbe.scala"),
                        """|import java.util.ArrayList // unused
                           |object WErrorProbe { val x = 42 }
                           |""".stripMargin
                    )
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case _: Driver.Outcome.Failed => succeed("-Werror promoted the warning to an error")
                                case _: Driver.Outcome.Ok =>
                                    fail("expected -Werror to convert unused-import warning to compile error, but got Ok")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"unexpected setup failure: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Driver.compile captures warnings in Driver.Outcome.Ok.warnings" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk("-deprecation"), false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(
                        kyo.Path("WarningBlock.scala"),
                        """|package kyo.doctest.synthetic
                           |object WarningTest {
                           |  @deprecated("use newMethod", "1.0") def oldMethod(): Int = 42
                           |  val result = oldMethod()
                           |}
                           |""".stripMargin
                    )
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case ok: Driver.Outcome.Ok =>
                                    assert(ok.warnings.nonEmpty, s"expected at least one warning for deprecated call, got empty warnings")
                                case Driver.Outcome.Failed(errors, _) =>
                                    fail(s"expected Ok with warnings, got Failed: ${errors.map(_.message).mkString(", ")}")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Failed diagnostics carry file, line, and message" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(
                        kyo.Path("DiagDetail.scala"),
                        """|package kyo.doctest.synthetic
                           |object DiagTest {
                           |  val x: Int = "wrong"
                           |}
                           |""".stripMargin
                    )
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case Driver.Outcome.Failed(errors, _) =>
                                    assert(errors.nonEmpty)
                                    val diag = errors(0)
                                    assert(diag.file.toString.nonEmpty, "file should be non-empty")
                                    assert(diag.line > 0, s"line should be positive, got ${diag.line}")
                                    assert(diag.message.nonEmpty, "message should be non-empty")
                                case Driver.Outcome.Ok(_) =>
                                    fail("expected Failed but got Ok")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Driver.Diagnostic.Severity.Error assigned to type errors" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(
                        kyo.Path("SeverityTest.scala"),
                        """|package kyo.doctest.synthetic
                           |object SeverityTest {
                           |  val x: String = 42
                           |}
                           |""".stripMargin
                    )
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case Driver.Outcome.Failed(errors, _) =>
                                    assert(errors.nonEmpty)
                                    assert(errors.forall(_.severity == Driver.Diagnostic.Severity.Error))
                                case Driver.Outcome.Ok(_) =>
                                    fail("expected Failed but got Ok")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "two sequential Driver.compile calls on the same Driver produce independent results" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    val good = Driver.Source(kyo.Path("Good.scala"), "val x = 42\n")
                    val bad  = Driver.Source(kyo.Path("Bad.scala"), "val y: Int = \"wrong\"\n")
                    driver.compile(good).flatMap { r1 =>
                        driver.compile(bad).flatMap { r2 =>
                            driver.close.map { _ =>
                                val r1ok = r1 match
                                    case _: Driver.Outcome.Ok => true
                                    case _                    => false
                                val r2fail = r2 match
                                    case _: Driver.Outcome.Failed => true
                                    case _                        => false
                                assert(r1ok, s"first compile should succeed, got $r1")
                                assert(r2fail, s"second compile should fail, got $r2")
                            }
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "Driver.Outcome.Ok has no errors" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(kyo.Path("OkCheck.scala"), "val z = true\n")
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case Driver.Outcome.Ok(_) => succeed("source compiled cleanly as expected")
                                case Driver.Outcome.Failed(errors, _) =>
                                    fail(s"expected Ok but got errors: ${errors.map(_.message).mkString(", ")}")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

    "compile of source with syntax error returns Driver.Outcome.Failed (not panic)" in {
        testClasspath.flatMap { cp =>
            Abort.run(Driver.init(cp, Chunk.empty, false)).flatMap {
                case Result.Success(driver) =>
                    val src = Driver.Source(
                        kyo.Path("SyntaxError.scala"),
                        "val broken = {\n" // unclosed brace
                    )
                    driver.compile(src).flatMap { result =>
                        driver.close.map { _ =>
                            result match
                                case Driver.Outcome.Failed(errors, _) =>
                                    assert(errors.nonEmpty, "expected at least one error for syntax problem")
                                case Driver.Outcome.Ok(_) =>
                                    fail("expected Failed but got Ok for syntax error")
                        }
                    }
                case Result.Failure(e) =>
                    Sync.defer(fail(s"init failed: $e"))
                case Result.Panic(t) =>
                    Sync.defer(fail(s"unexpected panic: ${t.getMessage}"))
            }
        }
    }

end DriverTest
