package kyo.test.runner.internal

import kyo.Absent
import kyo.Chunk
import kyo.ChunkBuilder
import kyo.Maybe
import kyo.Present
import kyo.test.TestReport
import kyo.test.TestResult
import scala.annotation.tailrec

/** One leaf as the run summary sees it: its path, its outcome, and the one-line reason [[Summary]] prints for a non-passing leaf.
  *
  * It is the unit a Scala.js or Scala Native worker sends to the controller. Those test adapters run one test process per sbt thread, and
  * only the controller's `done()` reaches the log, so a worker ships its leaves through the framework message channel (`send` into the
  * controller's `receiveMessage`). A record carries exactly what the summary renders and nothing else, which keeps a message small.
  */
final private[runner] case class LeafRecord(path: Chunk[String], kind: LeafRecord.Kind, reason: String) derives CanEqual

private[runner] object LeafRecord:

    enum Kind derives CanEqual:
        case Passed, Failed, Cancelled, Pending, Ignored, TimedOut, Skipped

    def of(path: Chunk[String], result: TestResult): LeafRecord =
        result match
            case _: TestResult.Passed                    => LeafRecord(path, Kind.Passed, "")
            case TestResult.Failed(diagram, cause, _, _) =>
                val text = if diagram.nonEmpty then diagram else cause.fold("")(t => t.getClass.getName + ": " + t.getMessage)
                LeafRecord(path, Kind.Failed, boundedFirstLine(text))
            case TestResult.Cancelled(reason, _) => LeafRecord(path, Kind.Cancelled, boundedFirstLine(reason))
            case _: TestResult.Pending           => LeafRecord(path, Kind.Pending, "")
            case _: TestResult.Ignored           => LeafRecord(path, Kind.Ignored, "")
            case TestResult.TimedOut(limit)      => LeafRecord(path, Kind.TimedOut, "limit: " + formatDuration(limit))
            case _: TestResult.Skipped           => LeafRecord(path, Kind.Skipped, "")

    def of(report: TestReport): Chunk[LeafRecord] =
        Chunk.from(report.suiteReports.iterator.flatMap(_.leafResults.iterator).map((path, result) => of(path, result)))

    // The message format is `kyo-test-leaves/1` then, per record, the kind's ordinal, the path's segment count, each segment, and the
    // reason, where every field is `<length>:<text>`. Length prefixes make any character, including ':' and newlines, safe in a field.
    private val Header = "kyo-test-leaves/1\n"

    /** The adapters ship a message with `DataOutputStream.writeUTF`, which throws above 65535 bytes of modified UTF-8. At most 3 bytes per
      * char, a message of at most this many chars always fits.
      */
    private val MaxMessageChars = 20000

    /** Longest path segment kept in a message. A record whose segments together would not fit one message keeps a prefix of each. */
    private val MaxSegmentChars = 200

    /** Encodes the records as one or more messages, each within the adapters' message size limit. */
    def encode(records: Chunk[LeafRecord]): Chunk[String] =
        val budget   = MaxMessageChars - Header.length
        val messages = ChunkBuilder.init[String]
        val current  = new StringBuilder
        records.foreach { record =>
            val encoded = encodeOne(record, budget)
            if current.nonEmpty && current.length + encoded.length > budget then
                kyo.discard(messages += Header + current.toString)
                current.clear()
            kyo.discard(current.append(encoded))
        }
        if current.nonEmpty then kyo.discard(messages += Header + current.toString)
        messages.result()
    end encode

    /** The records in a message from [[encode]], or `Absent` when `message` is not one. */
    def decode(message: String): Maybe[Chunk[LeafRecord]] =
        if !message.startsWith(Header) then Absent
        else
            val kinds = Kind.values

            // Reads `<length>:<text>` at `at`; returns the text and the index after it, or Absent when malformed.
            def field(at: Int): Maybe[(String, Int)] =
                val colon = message.indexOf(':', at)
                if colon <= at then Absent
                else
                    message.substring(at, colon).toIntOption match
                        case Some(length) if length >= 0 && colon + 1 + length <= message.length =>
                            Present((message.substring(colon + 1, colon + 1 + length), colon + 1 + length))
                        case _ => Absent
                end if
            end field

            def number(at: Int): Maybe[(Int, Int)] =
                field(at).flatMap((text, next) => Maybe.fromOption(text.toIntOption).map((_, next)))

            @tailrec def segments(at: Int, remaining: Int, acc: Chunk[String]): Maybe[(Chunk[String], Int)] =
                if remaining == 0 then Present((acc, at))
                else
                    field(at) match
                        case Present((segment, next)) => segments(next, remaining - 1, acc.append(segment))
                        case Absent                   => Absent

            @tailrec def records(at: Int, acc: Chunk[LeafRecord]): Maybe[Chunk[LeafRecord]] =
                if at == message.length then Present(acc)
                else
                    val record =
                        for
                            (ordinal, afterKind)  <- number(at)
                            kind                  <- if ordinal >= 0 && ordinal < kinds.length then Present(kinds(ordinal)) else Absent
                            (count, afterCount)   <- number(afterKind)
                            (path, afterPath)     <- if count >= 0 then segments(afterCount, count, Chunk.empty) else Absent
                            (reason, afterReason) <- field(afterPath)
                        yield (LeafRecord(path, kind, reason), afterReason)
                    record match
                        case Present((leaf, next)) => records(next, acc.append(leaf))
                        case Absent                => Absent
                    end match
            records(Header.length, Chunk.empty)
        end if
    end decode

    private def encodeOne(record: LeafRecord, budget: Int): String =
        val full = render(record, record.path)
        if full.length <= budget then full
        else
            val capped = record.path.map(_.take(MaxSegmentChars))
            // A reason is bounded by MaxReasonChars, so dropping trailing segments always reaches the budget.
            @tailrec def fit(keep: Int): String =
                val text = render(record, capped.take(keep))
                if text.length <= budget || keep == 0 then text else fit(keep - 1)
            fit(capped.size)
        end if
    end encodeOne

    private def render(record: LeafRecord, path: Chunk[String]): String =
        val sb                        = new StringBuilder
        def field(text: String): Unit = kyo.discard(sb.append(text.length).append(':').append(text))
        field(record.kind.ordinal.toString)
        field(path.size.toString)
        path.foreach(field)
        field(record.reason)
        sb.toString
    end render

    /** Upper bound on a reason line. `Runner.done()` returns the summary over the same size-limited channel, and a failing leaf whose
      * diagram is one very long line (a rendered SVG with no newlines) would otherwise carry all of it. The full diagram is still in the
      * per-leaf reporter output.
      */
    private val MaxReasonChars = 500

    private def boundedFirstLine(s: String): String =
        val line = s.linesIterator.nextOption().getOrElse("")
        if line.length <= MaxReasonChars then line
        else line.substring(0, MaxReasonChars) + s"... (${line.length} chars total)"
    end boundedFirstLine

    private def formatDuration(d: kyo.Duration): String =
        val ms = d.toMillis
        if ms < 1000 then s"${ms}ms"
        else if ms < 60000 then
            val s   = ms / 1000
            val rem = ms % 1000
            if rem == 0 then s"${s}s" else f"${s}.${rem / 100}s"
        else
            val m   = ms / 60000
            val sec = (ms % 60000) / 1000
            if sec == 0 then s"${m}m" else s"${m}m ${sec}s"
        end if
    end formatDuration

end LeafRecord
