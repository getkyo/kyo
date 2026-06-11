package kyo.test.runner.internal

import kyo.*
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult

/** Tests for [[Summary.render]]: golden-render tests covering all 7 result kinds (plan tests 1-5 + 13-14 + 17-18). */
class SummaryTest extends kyo.test.Test[Any]:

    private val oneMilli: Duration     = 1L.millis
    private val zeroDuration: Duration = Duration.Zero

    private def leafResult(r: TestResult): (Chunk[String], TestResult) =
        (Chunk("suite", "leaf"), r)

    private def reportOf(results: (Chunk[String], TestResult)*): Iterable[TestReport] =
        Iterable(
            TestReport(
                Chunk(
                    SuiteReport("TestSuite", Chunk(results*), zeroDuration)
                )
            )
        )

    // ── Test 1: one Passed leaf ───────────────────────────────────────────────────────────────────

    "test-1: one Passed leaf renders all-zero except passed=1" in {
        val summary = Summary.render(
            reportOf(leafResult(TestResult.Passed(oneMilli))),
            Chunk.empty,
            Chunk.empty
        )
        assert(summary == "kyo-test: 1 tests, 1 passed, 0 failed, 0 cancelled, 0 pending, 0 ignored, 0 timed out, 0 skipped"): Unit
    }

    // ── Test 2: one of each of the 7 result kinds ─────────────────────────────────────────────────

    "test-2: one of each 7 result kinds renders all counters as 1" in {
        val summary = Summary.render(
            reportOf(
                leafResult(TestResult.Passed(oneMilli)),
                leafResult(TestResult.Failed("x == y", Maybe.empty, oneMilli)),
                leafResult(TestResult.Cancelled("interrupted", oneMilli)),
                leafResult(TestResult.Pending("not yet")),
                leafResult(TestResult.Ignored("")),
                leafResult(TestResult.TimedOut(5L.seconds)),
                leafResult(TestResult.Skipped("filtered"))
            ),
            Chunk.empty,
            Chunk.empty
        )
        // Summary line is the first line; failures append a TOTAL FAILURES block after it.
        assert(summary.startsWith("kyo-test: 7 tests, 1 passed, 1 failed, 1 cancelled, 1 pending, 1 ignored, 1 timed out, 1 skipped")): Unit
        assert(summary.contains("TOTAL FAILURES (3)")): Unit
    }

    // ── Test 3: empty reports and empty positional args ───────────────────────────────────────────

    "test-3: empty reports and empty positional args renders all-zero line" in {
        val summary = Summary.render(Iterable.empty, Chunk.empty, Chunk.empty)
        assert(summary == "kyo-test: 0 tests, 0 passed, 0 failed, 0 cancelled, 0 pending, 0 ignored, 0 timed out, 0 skipped"): Unit
    }

    // ── Test 4: empty reports with a positional arg renders no-match diagnostic ──────────────────

    "test-4: empty reports with positionalArgs renders no-tests-matched diagnostic" in {
        val summary = Summary.render(
            Iterable.empty,
            Chunk.empty,
            Chunk("kyo.DoesNotExist")
        )
        assert(summary == "kyo-test: no tests matched the filter (kyo.DoesNotExist)"): Unit
    }

    // ── Test 5: discoveryErrors appends warning line ──────────────────────────────────────────────

    "test-5: discoveryErrors appends warning line" in {
        val summary = Summary.render(
            Iterable.empty,
            Chunk("missing class kyo.Foo"),
            Chunk.empty
        )
        assert(summary.contains("warning: 1 suite(s) failed to load: missing class kyo.Foo")): Unit
    }

    // ── Test 13: byte-identical output from two Summary.render calls (cross-platform invariant) ──

    "test-13: Summary.render is deterministic: two calls with identical input produce identical strings on all platforms" in {
        val reports = reportOf(
            leafResult(TestResult.Passed(oneMilli)),
            leafResult(TestResult.Failed("a == b", Maybe.empty, oneMilli))
        )
        val first  = Summary.render(reports, Chunk.empty, Chunk.empty)
        val second = Summary.render(reports, Chunk.empty, Chunk.empty)
        assert(first == second): Unit
        // Golden check: exact output must match on JVM, JS, and Native.
        // The summary line is followed by a TOTAL FAILURES block because there is 1 failing leaf.
        val expectedSummaryLine = "kyo-test: 2 tests, 1 passed, 1 failed, 0 cancelled, 0 pending, 0 ignored, 0 timed out, 0 skipped"
        assert(first.startsWith(expectedSummaryLine)): Unit
        assert(first.contains("TOTAL FAILURES (1)")): Unit
        assert(first.contains("suite > leaf")): Unit
        assert(first.contains("[FAIL]")): Unit
        assert(first.contains("a == b")): Unit
    }

    // ── Test 14: byte-identical output when given the same multi-report input ─────────────────────

    "test-14: Summary.render produces the same string for equivalent multi-report inputs" in {
        val sr = SuiteReport(
            "S",
            Chunk(
                (Chunk("a"), TestResult.Passed(oneMilli)),
                (Chunk("b"), TestResult.Passed(oneMilli))
            ),
            zeroDuration
        )
        val reports = Iterable(TestReport(Chunk(sr)))
        val first   = Summary.render(reports, Chunk.empty, Chunk.empty)
        val second  = Summary.render(reports, Chunk.empty, Chunk.empty)
        assert(first == second): Unit
        assert(first == "kyo-test: 2 tests, 2 passed, 0 failed, 0 cancelled, 0 pending, 0 ignored, 0 timed out, 0 skipped"): Unit
    }

    // ── Test 17: done() includes discovery warning when discoveryErrors non-empty ─────────────────

    "test-17: discovery error Chunk(\"foo\") is reflected in summary warning" in {
        val summary = Summary.render(
            Iterable.empty,
            Chunk("foo"),
            Chunk.empty
        )
        assert(summary.contains("warning: 1 suite(s) failed to load: foo")): Unit
    }

    // ── Test 18: end-to-end sum invariant (3 passed + 2 failed = 5 total) ────────────────────────

    "test-18: 3 Passed + 2 Failed leaves render 5 total, 3 passed, 2 failed with sum invariant" in {
        val sr = SuiteReport(
            "EndToEndSuite",
            Chunk(
                (Chunk("p1"), TestResult.Passed(oneMilli)),
                (Chunk("p2"), TestResult.Passed(oneMilli)),
                (Chunk("p3"), TestResult.Passed(oneMilli)),
                (Chunk("f1"), TestResult.Failed("x", Maybe.empty, oneMilli)),
                (Chunk("f2"), TestResult.Failed("y", Maybe.empty, oneMilli))
            ),
            zeroDuration
        )
        val reports = Iterable(TestReport(Chunk(sr)))
        val summary = Summary.render(reports, Chunk.empty, Chunk.empty)

        // The summary line must mention 5 tests total and the correct per-kind counts
        assert(summary.contains("5 tests")): Unit
        assert(summary.contains("3 passed")): Unit
        assert(summary.contains("2 failed")): Unit

        // Sum invariant: 3 + 2 = 5 (all other counters are 0 for this input); summary line comes first
        val expectedLine = "kyo-test: 5 tests, 3 passed, 2 failed, 0 cancelled, 0 pending, 0 ignored, 0 timed out, 0 skipped"
        assert(summary.startsWith(expectedLine)): Unit
        // TOTAL FAILURES block lists both failing leaves
        assert(summary.contains("TOTAL FAILURES (2)")): Unit
    }

    // ── TOTAL FAILURES block ───────────────────────────────────────────────────────────────────

    "phase10-test-1: 2 failing leaves among many passing produce TOTAL FAILURES (2) naming both paths with one-line reasons" in {
        val sr = SuiteReport(
            "MySuite",
            Chunk(
                (Chunk("MySuite", "p1"), TestResult.Passed(oneMilli)),
                (Chunk("MySuite", "p2"), TestResult.Passed(oneMilli)),
                (Chunk("MySuite", "p3"), TestResult.Passed(oneMilli)),
                (Chunk("MySuite", "f1"), TestResult.Failed("x != y", Maybe.empty, oneMilli)),
                (Chunk("MySuite", "f2"), TestResult.Failed("a > b", Maybe.empty, oneMilli))
            ),
            zeroDuration
        )
        val summary = Summary.render(Iterable(TestReport(Chunk(sr))), Chunk.empty, Chunk.empty)
        assert(summary.contains("TOTAL FAILURES (2)"), s"Expected TOTAL FAILURES (2) in:\n$summary"): Unit
        assert(summary.contains("MySuite > f1"), s"Expected path MySuite > f1 in:\n$summary"): Unit
        assert(summary.contains("MySuite > f2"), s"Expected path MySuite > f2 in:\n$summary"): Unit
        assert(summary.contains("x != y"), s"Expected one-line reason 'x != y' in:\n$summary"): Unit
        assert(summary.contains("a > b"), s"Expected one-line reason 'a > b' in:\n$summary"): Unit
    }

    "phase10-test-2: all-green report produces no TOTAL FAILURES section" in {
        val sr = SuiteReport(
            "GreenSuite",
            Chunk(
                (Chunk("p1"), TestResult.Passed(oneMilli)),
                (Chunk("p2"), TestResult.Passed(oneMilli))
            ),
            zeroDuration
        )
        val summary = Summary.render(Iterable(TestReport(Chunk(sr))), Chunk.empty, Chunk.empty)
        assert(!summary.contains("TOTAL FAILURES"), s"Expected no TOTAL FAILURES in all-green output:\n$summary"): Unit
        assert(!summary.contains("FAILURES"), s"Expected no FAILURES section in all-green output:\n$summary"): Unit
    }

    "phase10-test-3: TimedOut and Cancelled leaves appear; Pending Skipped Ignored do not" in {
        val sr = SuiteReport(
            "MixedSuite",
            Chunk(
                (Chunk("MixedSuite", "t1"), TestResult.TimedOut(30L.seconds)),
                (Chunk("MixedSuite", "c1"), TestResult.Cancelled("setup failed", oneMilli)),
                (Chunk("MixedSuite", "pnd"), TestResult.Pending("not ready")),
                (Chunk("MixedSuite", "skp"), TestResult.Skipped("filtered")),
                (Chunk("MixedSuite", "ign"), TestResult.Ignored(""))
            ),
            zeroDuration
        )
        val summary = Summary.render(Iterable(TestReport(Chunk(sr))), Chunk.empty, Chunk.empty)
        assert(summary.contains("TOTAL FAILURES (2)"), s"Expected TOTAL FAILURES (2) in:\n$summary"): Unit
        assert(summary.contains("[TIMEOUT]"), s"Expected [TIMEOUT] tag in:\n$summary"): Unit
        assert(summary.contains("[CANCELLED]"), s"Expected [CANCELLED] tag in:\n$summary"): Unit
        assert(summary.contains("MixedSuite > t1"), s"Expected path MixedSuite > t1 in:\n$summary"): Unit
        assert(summary.contains("MixedSuite > c1"), s"Expected path MixedSuite > c1 in:\n$summary"): Unit
        assert(!summary.contains("MixedSuite > pnd"), s"Pending leaf must not appear in TOTAL FAILURES:\n$summary"): Unit
        assert(!summary.contains("MixedSuite > skp"), s"Skipped leaf must not appear in TOTAL FAILURES:\n$summary"): Unit
        assert(!summary.contains("MixedSuite > ign"), s"Ignored leaf must not appear in TOTAL FAILURES:\n$summary"): Unit
    }

    "a huge single-line failure diagram is bounded in the summary (native writeUTF RPC safety)" in {
        // The summary string is what Runner.done() returns; on Scala Native sbt ships it back over the
        // test-interface RPC via DataOutputStream.writeUTF, which caps at 65535 bytes. A failing leaf
        // whose diagram is a single ~500KB line (e.g. a rendered SVG with no newlines) must not make the
        // per-leaf reason carry the whole thing, or the summary overflows that RPC and crashes the
        // entire suite's transport.
        val hugeLine = "x" * 500000
        val summary = Summary.render(
            reportOf(leafResult(TestResult.Failed(hugeLine, Maybe.empty, oneMilli))),
            Chunk.empty,
            Chunk.empty
        )
        assert(summary.contains("TOTAL FAILURES (1)"), s"expected TOTAL FAILURES (1)"): Unit
        assert(summary.length < 64000, s"summary length ${summary.length} exceeds the 64KB writeUTF cap"): Unit
    }

    "many failing leaves keep the summary under the writeUTF cap (the real native crash)" in {
        // The actual Scala Native crash: when very many leaves fail/cancel (e.g. ~1300 kyo-ui leaves
        // cancelled because Chrome is unavailable on linux-arm64, each with a long reason), the TOTAL
        // FAILURES block lists them all and the summary overflows writeUTF. Per-line capping alone is not
        // enough; the whole block must be bounded.
        val reason = "chrome-headless-shell unavailable on linux-arm64; install chromium and pass Browser.LaunchConfig.chromium. " * 3
        val leaves = (1 to 3000).map(i => (Chunk("Suite", s"leaf$i"), TestResult.Cancelled(reason, oneMilli): TestResult))
        val summary = Summary.render(
            Iterable(TestReport(Chunk(SuiteReport("Suite", Chunk.from(leaves), zeroDuration)))),
            Chunk.empty,
            Chunk.empty
        )
        assert(summary.contains("TOTAL FAILURES (3000)"), s"expected the full count in the header"): Unit
        assert(
            summary.contains("[CANCELLED] x3000"),
            s"expected the 3000 same-reason cancellations collapsed into one grouped line:\n${summary.take(500)}"
        ): Unit
        assert(!summary.contains("leaf2999"), s"grouped leaves should not list individual paths:\n${summary.take(500)}"): Unit
        assert(summary.length < 64000, s"summary length ${summary.length} exceeds the 64KB writeUTF cap"): Unit
    }

    "distinct reasons are not grouped (each failing leaf keeps its own line + path)" in {
        val summary = Summary.render(
            reportOf(
                leafResult(TestResult.Failed("a == b", Maybe.empty, oneMilli)),
                leafResult(TestResult.Failed("c == d", Maybe.empty, oneMilli))
            ),
            Chunk.empty,
            Chunk.empty
        )
        assert(summary.contains("TOTAL FAILURES (2)"), summary): Unit
        assert(summary.contains("a == b") && summary.contains("c == d"), s"distinct reasons must both appear:\n$summary"): Unit
        assert(!summary.contains(" x2"), s"distinct reasons must not be grouped:\n$summary"): Unit
    }

    "many DISTINCT multi-byte failures stay under the writeUTF byte cap (not just the char cap)" in {
        // Grouping does not help when every failure is distinct, so the backstop must hold. writeUTF caps at
        // 65535 *bytes* of modified UTF-8 (up to 3 bytes/char), so a char-only bound is not enough: 5000
        // distinct reasons built from 3-byte CJK characters would be ~150KB of bytes while well under any
        // character cap. The byte-aware guard must keep the rendered summary under the real byte cap.
        val leaves = (1 to 5000).map { i =>
            val reason = ("中" * 300) + i.toString // 300 CJK chars (3 bytes each) + a unique suffix
            (Chunk("Suite", s"leaf$i"), TestResult.Cancelled(reason, oneMilli): TestResult)
        }
        val summary = Summary.render(
            Iterable(TestReport(Chunk(SuiteReport("Suite", Chunk.from(leaves), zeroDuration)))),
            Chunk.empty,
            Chunk.empty
        )
        val byteLen = summary.foldLeft(0) { (acc, c) =>
            val code = c.toInt
            acc + (if code >= 0x0001 && code <= 0x007f then 1 else if code <= 0x07ff then 2 else 3)
        }
        assert(byteLen < 65535, s"summary modified-UTF-8 byte length $byteLen exceeds the 65535 writeUTF cap"): Unit
        assert(summary.contains("TOTAL FAILURES (5000)"), s"header must still carry the true count:\n${summary.take(200)}"): Unit
    }

end SummaryTest
