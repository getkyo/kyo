package kyo.test.runner.internal

import kyo.Chunk
import kyo.Maybe
import kyo.test.runner.CombinedReporter
import kyo.test.runner.TapReporter

/** Tests for the post-parse --reporter= translation pass in Args.parse.
  *
  * Test 4 (--reporter=tap,junit-xml:PATH yields CombinedReporter) lives in jvm/ArgsParserJUnitXmlTest.scala because it requires
  * java.nio.file.Files (JVM-only).
  */
class ArgsParserTest extends kyo.test.Test[Any]:

    // ── Test 5: --reporter=junit-xml (no path) yields Result.Error ──────────

    "test-5: --reporter=junit-xml without path yields Result.Error containing 'junit-xml requires a path'" in {
        val result = Args.parse(Array("--reporter=junit-xml"))
        result match
            case Args.Result.Error(msg) =>
                assert(
                    msg.contains("junit-xml requires a path"),
                    s"expected 'junit-xml requires a path' in error message, got: $msg"
                ): Unit
            case other =>
                fail(s"expected Result.Error, got: $other")
        end match
    }

    // ── Test 6: --reporter=bogus yields Result.Error ─────────────────────────

    "test-6: --reporter=bogus yields Result.Error containing 'unknown reporter'" in {
        val result = Args.parse(Array("--reporter=bogus"))
        result match
            case Args.Result.Error(msg) =>
                assert(
                    msg.contains("unknown reporter"),
                    s"expected 'unknown reporter' in error message, got: $msg"
                ): Unit
            case other =>
                fail(s"expected Result.Error, got: $other")
        end match
    }

    // ── Test 7: --paralel=4 (typo) yields Result.Error ──────────────────────

    "test-7: --paralel=4 (typo'd flag) yields Result.Error indicating unknown argument" in {
        val result = Args.parse(Array("--paralel=4"))
        result match
            case Args.Result.Error(msg) =>
                assert(
                    msg.contains("unknown") || msg.contains("--paralel"),
                    s"expected 'unknown argument' indicator in error message, got: $msg"
                ): Unit
            case other =>
                fail(s"expected Result.Error for typo'd flag, got: $other")
        end match
    }

    // ── Test 8: TestFilter no longer accepts classInclude/classExclude ────────

    "test-8: TestFilter constructor no longer accepts classInclude or classExclude named arguments (compile-time check)" in {
        val errors = scala.compiletime.testing.typeCheckErrors(
            """kyo.test.TestFilter(classInclude = kyo.Chunk.empty)"""
        )
        assert(errors.nonEmpty, "expected compile error for classInclude named arg, but it compiled successfully"): Unit
    }

    // ── Bonus: single-reporter --reporter=console yields non-CombinedReporter ──

    "bonus: --reporter=console yields a single ConsoleReporter (not wrapped in CombinedReporter)" in {
        val result = Args.parse(Array("--reporter=console"))
        result match
            case Args.Result.Ok(parsed) =>
                parsed.config.reporter match
                    case Maybe.Present(r) =>
                        assert(
                            !r.isInstanceOf[CombinedReporter],
                            s"single reporter must not be wrapped in CombinedReporter, got ${r.getClass.getSimpleName}"
                        ): Unit
                    case Maybe.Absent =>
                        fail("expected Maybe.Present for --reporter=console, got Absent")
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

    // ── Bonus: --reporter=tap yields TapReporter directly ────────────────────

    "bonus: --reporter=tap yields a single TapReporter (not wrapped in CombinedReporter)" in {
        val result = Args.parse(Array("--reporter=tap"))
        result match
            case Args.Result.Ok(parsed) =>
                parsed.config.reporter match
                    case Maybe.Present(r: TapReporter) =>
                        assert(r.isInstanceOf[TapReporter]): Unit
                    case Maybe.Present(other) =>
                        fail(s"expected TapReporter, got ${other.getClass.getSimpleName}")
                    case Maybe.Absent =>
                        fail("expected Maybe.Present for --reporter=tap, got Absent")
            case other =>
                fail(s"expected Ok, got: $other")
        end match
    }

end ArgsParserTest
