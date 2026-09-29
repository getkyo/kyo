package kyo.test.runner.internal

import kyo.*
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult

class LeafRecordTest extends kyo.test.Test[Any]:

    private def roundTrip(records: Chunk[LeafRecord]): Chunk[LeafRecord] =
        Chunk.from(LeafRecord.encode(records).flatMap(message => LeafRecord.decode(message).getOrElse(Chunk.empty)))

    // What the adapters' DataOutputStream.writeUTF accepts: at most 65535 bytes of modified UTF-8.
    private def modifiedUtf8Bytes(s: String): Int =
        s.foldLeft(0)((n, c) => n + (if c >= '\u0001' && c <= '\u007f' then 1 else if c <= '߿' then 2 else 3))

    "of" - {
        "every result kind" in {
            val path = Chunk("suite", "leaf")
            assert(LeafRecord.of(path, TestResult.Passed(1L.millis)) == LeafRecord(path, LeafRecord.Kind.Passed, ""))
            assert(LeafRecord.of(path, TestResult.Failed("a\nb", Absent, 1L.millis)) == LeafRecord(path, LeafRecord.Kind.Failed, "a"))
            assert(LeafRecord.of(path, TestResult.Cancelled("why", 1L.millis)) == LeafRecord(path, LeafRecord.Kind.Cancelled, "why"))
            assert(LeafRecord.of(path, TestResult.Pending("later")) == LeafRecord(path, LeafRecord.Kind.Pending, ""))
            assert(LeafRecord.of(path, TestResult.Ignored("off")) == LeafRecord(path, LeafRecord.Kind.Ignored, ""))
            assert(LeafRecord.of(path, TestResult.TimedOut(2L.seconds)) == LeafRecord(path, LeafRecord.Kind.TimedOut, "limit: 2s"))
            assert(LeafRecord.of(path, TestResult.Skipped("no")) == LeafRecord(path, LeafRecord.Kind.Skipped, ""))
        }
        "a failure with no diagram reads its cause" in {
            val record = LeafRecord.of(Chunk("l"), TestResult.Failed("", Present(new IllegalStateException("boom")), 1L.millis))
            assert(record.reason == "java.lang.IllegalStateException: boom")
        }
        "a long reason line is bounded" in {
            val record = LeafRecord.of(Chunk("l"), TestResult.Failed("x" * 2000, Absent, 1L.millis))
            assert(record.reason == ("x" * 500) + "... (2000 chars total)")
        }
        "every leaf of every suite in a report" in {
            val report = TestReport(Chunk(
                SuiteReport(
                    "A",
                    Chunk((Chunk("a1"), TestResult.Passed(1L.millis)), (Chunk("a2"), TestResult.Passed(1L.millis))),
                    1L.millis
                ),
                SuiteReport("B", Chunk((Chunk("b1"), TestResult.Skipped("s"))), 1L.millis)
            ))
            assert(LeafRecord.of(report).map(_.path) == Chunk(Chunk("a1"), Chunk("a2"), Chunk("b1")))
        }
    }

    "encode and decode" - {
        "round-trip every kind" in {
            val records = Chunk.from(LeafRecord.Kind.values.map(kind => LeafRecord(Chunk("suite", kind.toString), kind, s"reason $kind")))
            assert(roundTrip(records) == records)
        }
        "round-trip fields holding the format's own characters, newlines and non-ASCII text" in {
            val records = Chunk(
                LeafRecord(Chunk("a:b", "12:x", "line1\nline2", ""), LeafRecord.Kind.Failed, "3:abc: é ✓ \u0000"),
                LeafRecord(Chunk.empty, LeafRecord.Kind.Passed, "")
            )
            assert(roundTrip(records) == records)
        }
        "no records encode to no messages" in {
            assert(LeafRecord.encode(Chunk.empty) == Chunk.empty)
        }
        "many leaves split into messages that each fit writeUTF" in {
            val records =
                Chunk.from((1 to 5000).map(i => LeafRecord(Chunk("suite", s"leaf number $i ✓"), LeafRecord.Kind.Failed, "é" * 400)))
            val messages = LeafRecord.encode(records)
            assert(messages.size > 1): Unit
            assert(messages.forall(m => modifiedUtf8Bytes(m) <= 65535)): Unit
            assert(roundTrip(records) == records)
        }
        "a record too large for one message keeps its kind and reason and a prefix of its path" in {
            val path     = Chunk.from((1 to 300).map(i => s"segment-$i-" + ("✓" * 300)))
            val record   = LeafRecord(path, LeafRecord.Kind.Failed, "reason")
            val messages = LeafRecord.encode(Chunk(record))
            assert(messages.size == 1): Unit
            assert(modifiedUtf8Bytes(messages(0)) <= 65535): Unit
            val decoded = LeafRecord.decode(messages(0)).getOrElse(Chunk.empty)
            assert(decoded.size == 1): Unit
            assert(decoded(0).kind == LeafRecord.Kind.Failed): Unit
            assert(decoded(0).reason == "reason"): Unit
            assert(decoded(0).path.nonEmpty && decoded(0).path.size < path.size): Unit
            assert(decoded(0).path.zip(path).forall((kept, full) => full.startsWith(kept)))
        }
        "a message that is not an encoding decodes to Absent" in {
            val valid = LeafRecord.encode(Chunk(LeafRecord(Chunk("s", "l"), LeafRecord.Kind.Passed, "")))(0)
            assert(LeafRecord.decode("") == Absent): Unit
            assert(LeafRecord.decode("hello") == Absent): Unit
            assert(LeafRecord.decode(valid.dropRight(1)) == Absent): Unit
            assert(LeafRecord.decode(valid + "x") == Absent): Unit
            assert(LeafRecord.decode(valid.replace("1:0", "2:99")) == Absent)
        }
    }

    "Summary.renderLeaves" - {
        "renders records exactly as the reports they came from" in {
            val reports = Iterable(TestReport(Chunk(SuiteReport(
                "S",
                Chunk(
                    (Chunk("s", "ok"), TestResult.Passed(1L.millis)),
                    (Chunk("s", "bad"), TestResult.Failed("expected 1", Absent, 1L.millis)),
                    (Chunk("s", "slow"), TestResult.TimedOut(3L.seconds))
                ),
                1L.millis
            ))))
            val records = Chunk.from(reports.flatMap(LeafRecord.of(_)))
            assert(Summary.renderLeaves(roundTrip(records), Chunk.empty, Chunk.empty) == Summary.render(reports, Chunk.empty, Chunk.empty))
        }
    }

end LeafRecordTest
