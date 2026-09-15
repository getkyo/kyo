package kyo.test.runner.internal

import kyo.*
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult

class WorkerReportsTest extends kyo.test.Test[Any]:

    private def suite(name: String, leaves: (Chunk[String], TestResult)*): TestReport =
        TestReport(Chunk(SuiteReport(name, Chunk(leaves*), 3L.millis)))

    private def decodeAll(messages: Chunk[String]): Chunk[TestReport] =
        messages.flatMap(message => Chunk.from(WorkerReports.decode(message).toOption))

    private def render(reports: Iterable[TestReport]): String =
        Summary.render(reports, Chunk.empty, Chunk.empty)

    private val everyKind = suite(
        "Mixed\tSuite",
        Chunk("passes")         -> TestResult.Passed(1L.millis),
        Chunk("group", "fails") -> TestResult.Failed("x == 5\n| 5", Maybe.empty, 1L.millis, attempts = 3),
        Chunk("throws")         -> TestResult.Failed("", Maybe(new IllegalStateException("broken\tstate")), 1L.millis),
        Chunk("slow")           -> TestResult.TimedOut(250L.millis),
        Chunk("cancelled")      -> TestResult.Cancelled("runs only in a browser; this host is Node\nsecond line", 1L.millis),
        Chunk("pending")        -> TestResult.Pending("known"),
        Chunk("ignored")        -> TestResult.Ignored("parked"),
        Chunk("skipped")        -> TestResult.Skipped("not focused"),
        Chunk("back\\slash", "tab\tand\nnewline") -> TestResult.Failed("café \\ \t", Maybe.empty, 1L.millis)
    )

    "a summary rendered from the decoded messages equals the summary of the original report" in {
        val messages = WorkerReports.encode(everyKind)
        assert(messages.size == 1)
        assert(messages.forall(message => WorkerReports.decode(message).isDefined))
        assert(render(decodeAll(messages)) == render(Chunk(everyKind)))
    }

    "the decoded leaves keep each path, status, and the reason the summary shows" in {
        val decoded = decodeAll(WorkerReports.encode(everyKind)).flatMap(_.suiteReports)
        assert(decoded.map(_.name) == Chunk("Mixed\tSuite"))
        val leaves = decoded.head.leafResults
        assert(leaves.map(_._1) == everyKind.suiteReports.head.leafResults.map(_._1))
        assert(leaves(1)._2 == TestResult.Failed("x == 5", Maybe.empty, Duration.Zero))
        assert(leaves(2)._2 == TestResult.Failed("java.lang.IllegalStateException: broken\tstate", Maybe.empty, Duration.Zero))
        assert(leaves(3)._2 == TestResult.TimedOut(250L.millis))
        assert(leaves(4)._2 == TestResult.Cancelled("runs only in a browser; this host is Node", Duration.Zero))
        assert(leaves(8)._1 == Chunk("back\\slash", "tab\tand\nnewline"))
    }

    "a large suite is split into messages that each fit the native test interface's string limit" in {
        val reason = "中" * 400
        val big    = suite("Big", (0 until 3000).map(i => Chunk("group", s"leaf-$i") -> TestResult.Failed(reason, Maybe.empty, 1L.millis))*)
        val messages = WorkerReports.encode(big)
        assert(messages.size > 1)
        // Every character takes at most 3 bytes of modified UTF-8, the interface's encoding, whose limit is 65535 bytes.
        assert(messages.forall(_.length * 3 <= 65535))
        val decoded = decodeAll(messages)
        assert(decoded.flatMap(_.suiteReports).map(_.leafResults.size).sum == 3000)
        assert(render(decoded) == render(Chunk(big)))
    }

    "a path longer than the summary shows is cut without changing the summary" in {
        // Uncut, this path alone is 60000 characters, past what one message can carry.
        val long     = suite("Long", Chunk("a" * 30000, "b" * 30000) -> TestResult.Failed("boom", Maybe.empty, 1L.millis))
        val messages = WorkerReports.encode(long)
        assert(messages.forall(_.length * 3 <= 65535))
        assert(render(decodeAll(messages)) == render(Chunk(long)))
    }

    "a suite with no leaves is still reported" in {
        val empty   = suite("Empty")
        val decoded = decodeAll(WorkerReports.encode(empty))
        assert(decoded.flatMap(_.suiteReports).map(s => (s.name, s.leafResults.size)) == Chunk(("Empty", 0)))
    }

    "a message the runner did not send decodes to nothing" in {
        assert(WorkerReports.decode("") == Maybe.empty)
        assert(WorkerReports.decode("some other framework's message") == Maybe.empty)
        assert(WorkerReports.decode("kyo-test-report\nSuite\nexploded\t0\t\tleaf") == Maybe.empty)
        assert(WorkerReports.decode("kyo-test-report\nSuite\ntimedOut\tsoon\t\tleaf") == Maybe.empty)
    }

end WorkerReportsTest
