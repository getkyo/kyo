package kyo.test.runner.internal

import kyo.Chunk
import kyo.Duration
import kyo.Maybe
import kyo.discard
import kyo.test.SuiteReport
import kyo.test.TestReport
import kyo.test.TestResult

/** The messages a worker runner sends its controller so the controller's summary counts the suites the worker ran.
  *
  * The Scala.js and Scala Native test adapters run a task on a worker runner, in a process of its own, when sbt executes the task on a
  * thread other than the one that created the controller, and they print only the controller's `done()`. The test interface gives the two
  * runners a message channel for this: the worker's `send` reaches the controller's `receiveMessage`.
  *
  * A message carries what [[Summary.render]] reads from a leaf: its path, its status, and the one-line reason the summary shows, so a
  * summary rendered from decoded reports equals one rendered from the originals. A suite's leaves are split across messages that each fit
  * the Scala Native interface's 65535-byte string limit, whatever characters they hold; a path longer than the summary ever shows is cut
  * to the part it shows.
  */
private[internal] object WorkerReports:

    private val header = "kyo-test-report"

    // A modified-UTF-8 character takes at most 3 bytes, so this many characters always fit the 65535-byte limit with room to spare.
    private val MaxMessageChars = 20000

    // Summary cuts a failure line to 1000 characters, so a longer path never shows past this; escaping at most doubles it, which keeps a
    // leaf line, reason included, well under MaxMessageChars.
    private val MaxPathChars = 2000

    /** The messages that carry `report`, in order. */
    def encode(report: TestReport): Chunk[String] =
        Chunk.from(report.suiteReports.iterator.flatMap(encodeSuite))

    /** The report a message carries, or `Absent` for a message this runner did not send. */
    def decode(message: String): Maybe[TestReport] =
        val lines = message.split("\n", -1)
        if lines.length < 2 || lines(0) != header then Maybe.empty
        else
            val leaves = Chunk.from(lines.iterator.drop(2).map(decodeLeaf))
            if leaves.exists(_.isEmpty) then Maybe.empty
            else Maybe(TestReport(Chunk(SuiteReport(unescape(lines(1)), leaves.flatMap(_.toOption), Duration.Zero))))
            end if
        end if
    end decode

    private def encodeSuite(suite: SuiteReport): Iterator[String] =
        val prefix  = header + "\n" + escape(suite.name)
        val encoded = suite.leafResults.iterator.map(encodeLeaf)
        // One leaf line is bounded by its reason and path, so a message holds at least one leaf and grows until the next would not fit.
        val messages = scala.collection.mutable.ListBuffer.empty[String]
        val current  = new StringBuilder(prefix)
        encoded.foreach { line =>
            if current.length > prefix.length && current.length + 1 + line.length > MaxMessageChars then
                messages += current.toString
                current.clear()
                discard(current.append(prefix))
            end if
            discard(current.append('\n').append(line))
        }
        if current.length > prefix.length || messages.isEmpty then messages += current.toString
        messages.iterator
    end encodeSuite

    private def encodeLeaf(leaf: (Chunk[String], TestResult)): String =
        val (path, result) = leaf
        val (tag, nanos) = result match
            case _: TestResult.Passed       => ("passed", 0L)
            case _: TestResult.Failed       => ("failed", 0L)
            case TestResult.TimedOut(limit) => ("timedOut", limit.toNanos)
            case _: TestResult.Cancelled    => ("cancelled", 0L)
            case _: TestResult.Pending      => ("pending", 0L)
            case _: TestResult.Ignored      => ("ignored", 0L)
            case _: TestResult.Skipped      => ("skipped", 0L)
        (Iterator(tag, nanos.toString, escape(Summary.oneLineReason(result))) ++ boundedPath(path).iterator.map(escape)).mkString("\t")
    end encodeLeaf

    /** The leading segments of `path` up to [[MaxPathChars]] characters, the last one cut if it crosses the bound. */
    private def boundedPath(path: Chunk[String]): Chunk[String] =
        if path.iterator.map(_.length).sum <= MaxPathChars then path
        else
            val kept = Chunk.newBuilder[String]
            var used = 0
            path.foreach { segment =>
                if used < MaxPathChars then
                    kept += segment.take(MaxPathChars - used)
                    used += segment.length
            }
            kept.result()
    end boundedPath

    private def decodeLeaf(line: String): Maybe[(Chunk[String], TestResult)] =
        val fields = line.split("\t", -1)
        if fields.length < 3 then Maybe.empty
        else
            val reason = unescape(fields(2))
            val path   = Chunk.from(fields.iterator.drop(3).map(unescape))
            val result: Maybe[TestResult] = fields(0) match
                case "passed"    => Maybe(TestResult.Passed(Duration.Zero))
                case "failed"    => Maybe(TestResult.Failed(reason, Maybe.empty, Duration.Zero))
                case "timedOut"  => fields(1).toLongOption.fold(Maybe.empty)(nanos => Maybe(TestResult.TimedOut(Duration.fromNanos(nanos))))
                case "cancelled" => Maybe(TestResult.Cancelled(reason, Duration.Zero))
                case "pending"   => Maybe(TestResult.Pending(""))
                case "ignored"   => Maybe(TestResult.Ignored(""))
                case "skipped"   => Maybe(TestResult.Skipped(""))
                case _           => Maybe.empty
            result.map(path -> _)
        end if
    end decodeLeaf

    private def escape(value: String): String =
        val out = new StringBuilder(value.length)
        value.foreach {
            case '\\'  => discard(out.append("\\\\"))
            case '\t'  => discard(out.append("\\t"))
            case '\n'  => discard(out.append("\\n"))
            case '\r'  => discard(out.append("\\r"))
            case other => discard(out.append(other))
        }
        out.toString
    end escape

    private def unescape(value: String): String =
        val out     = new StringBuilder(value.length)
        var escaped = false
        value.foreach { c =>
            if escaped then
                discard(out.append(c match
                    case 't'   => '\t'
                    case 'n'   => '\n'
                    case 'r'   => '\r'
                    case other => other))
                escaped = false
            else if c == '\\' then escaped = true
            else discard(out.append(c))
        }
        out.toString
    end unescape

end WorkerReports
