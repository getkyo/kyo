package kyo.kernel.internal

import java.io.PrintWriter
import java.io.StringWriter
import kyo.*
import kyo.kernel.*

class TraceTest extends kyo.test.Test[Any]:

    sealed trait TestEffect extends ArrowEffect[Const[Int], Const[Int]]
    object TestEffect:
        def apply(i: Int): Int < TestEffect = ArrowEffect.suspend[Unit](Tag[TestEffect], i)
        def run[A, S](v: A < (TestEffect & S)) =
            ArrowEffect.handle(Tag[TestEffect], v)(
                [C] => (input, cont) => cont(input + 1)
            )
    end TestEffect

    def ex = new Exception("test exception")

    def boom[S](x: Int < S): Int < S = x.map(_ => throw ex)

    def evalOnly = boom(10).eval

    def withEffects =
        val x = TestEffect(1)
        val y = TestEffect(1)
        val z = Kyo.zip(x, y).map(_ + _).map(boom)
        TestEffect.run(z).eval
    end withEffects

    def loop(depth: Int): Int < Any =
        if depth == 0 then boom(depth)
        else ((depth - 1): Int < Any).map(loop(_))

    def repeatedFrames = loop(100).eval

    "jvm" - {
        "only eval".onlyJvm in {
            assertTrace(
                evalOnly,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:19)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:21)
                |	at                        def evalOnly = boom(10).eval @ kyo.kernel.internal.TraceTest.evalOnly(TraceTest.scala:23)
                """
            )
        }

        "with effects".onlyJvm in {
            assertTrace(
                withEffects,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:19)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:21)
                |	at          val z = Kyo.zip(x, y).map(_ + _).map(boom) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:28)
                |	at                    val z = Kyo.zip(x, y).map(_ + _) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:28)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:28)
                |	at                                                   ) @ kyo.kernel.internal.TraceTest.TestEffect$.run(TraceTest.scala:16)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:28)
                |	at                                                   ) @ kyo.kernel.internal.TraceTest.TestEffect$.run(TraceTest.scala:16)
                |	at                              TestEffect.run(z).eval @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                """
            )
        }

        "repeated frames".onlyJvm in {
            assertTrace(
                repeatedFrames,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:19)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:21)
                |	at          else ((depth - 1): Int < Any).map(loop(_)) @ kyo.kernel.internal.TraceTest.loop(TraceTest.scala:34)
                """
            )
        }
    }

    // TODO The logic that finds the position of the stack to insert the trace
    // frames breaks in JS because the generated JS doesn't keep the file names
    // and positions. It doesn't fail, only generates a stack trace with Kyo's
    // frames at the wrong poition.
    "js" - {
        "only eval".onlyJs.pendingUntilFixed(
            "JS does not preserve source file/line positions, so Kyo trace frames land at the wrong stack position"
        ) in {
            assertTrace(
                evalOnly,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:19)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:21)
                |	at                        def evalOnly = boom(10).eval @ kyo.kernel.internal.TraceTest.evalOnly(TraceTest.scala:23)
                """
            )
            ()
        }

        "with effects".onlyJs.pendingUntilFixed(
            "JS does not preserve source file/line positions, so Kyo trace frames land at the wrong stack position"
        ) in {
            assertTrace(
                withEffects,
                """
                |java.lang.Exception: test exception
                |	at kyo.kernel.internal.TraceTest.ex(TraceTest.scala:19)
                |	at  oom[S](x: Int < S): Int < S = x.map(_ => throw ex) @ kyo.kernel.internal.TraceTest.boom(TraceTest.scala:21)
                |	at          val z = Kyo.zip(x, y).map(_ + _).map(boom) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:28)
                |	at                    val z = Kyo.zip(x, y).map(_ + _) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:28)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:28)
                |	at                                                   ) @ kyo.kernel.internal.TraceTest.TestEffect$.run(TraceTest.scala:16)
                |	at                               val z = Kyo.zip(x, y) @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:28)
                |	at                                                   ) @ kyo.kernel.internal.TraceTest.TestEffect$.run(TraceTest.scala:16)
                |	at                              TestEffect.run(z).eval @ kyo.kernel.internal.TraceTest.withEffects(TraceTest.scala:29)
                """
            )
            ()
        }
    }

    "no trace if exception is NoStackTrace" - {
        "jvm".onlyJvm in {
            import scala.util.control.NoStackTrace
            assertTrace(
                throw new NoStackTrace {},
                "kyo.kernel.internal.TraceTest$$anon$1"
            )
        }
        "js".onlyJs in {
            import scala.util.control.NoStackTrace
            assertTrace(
                throw new NoStackTrace {},
                """
                |kyo.kernel.internal.TraceTest$$anon$1
                |  <no stack trace available>
                """
            )
        }
    }

    "bug #1172 null frames" in {
        val safepoint = Safepoint.get // Trace.Owner
        safepoint.pushFrame(null.asInstanceOf[Frame])
        safepoint.enrich(new Exception)
        succeed("runs without error: enrich with null frame does not throw (regression for #1172)")
    }

    "Trace.render emits orderedElements toString newest-first for a golden snapshot" in {
        val safepoint = Safepoint.get
        val frame     = summon[Frame]
        safepoint.pushFrame(frame)
        val snapshot = safepoint.saveTrace()
        val rendered = Trace.render(snapshot)
        assert(rendered.nonEmpty)
        assert(rendered.startsWith("at "))
        assert(rendered.contains(" @ kyo.kernel.internal.TraceTest."))
        val lines = rendered.linesIterator.toList
        assert(lines.forall(_.startsWith("at ")))
        val emptySnapshot = Trace.init
        assert(Trace.render(emptySnapshot) == "")
    }

    "Trace.render collapses consecutive identical frames into one line with a (xN) count" in {
        // Build a Trace directly from a known frame array, so the run length is deterministic and not
        // perturbed by the shared safepoint ring. Three consecutive identical frames must collapse into
        // a single line annotated with the repeat count, as a tight loop pushing the same frame would.
        val frame    = summon[Frame]
        val frames   = Array(frame, frame, frame)
        val snapshot = new Trace(frames, 3)
        val rendered = Trace.render(snapshot)
        val lines    = rendered.linesIterator.toList
        assert(lines.size == 1)
        assert(lines.head.startsWith("at "))
        assert(lines.head.endsWith(" (x3)"))
        assert(lines.head.contains(" @ kyo.kernel.internal.TraceTest."))
    }

    "Trace.render omits the count suffix for a single occurrence" in {
        val frame    = summon[Frame]
        val snapshot = new Trace(Array(frame), 1)
        val rendered = Trace.render(snapshot)
        val lines    = rendered.linesIterator.toList
        assert(lines.size == 1)
        assert(!lines.head.contains("(x"))
    }

    "Trace.render counts only consecutive repeats, not all occurrences" in {
        // A B A is two distinct runs of A (count 1 each), never one run of 2: only adjacent repeats fold.
        val a = summon[Frame]
        val b = summon[Frame]
        assert(a != b)
        val snapshot = new Trace(Array(a, b, a), 3)
        val rendered = Trace.render(snapshot)
        val lines    = rendered.linesIterator.toList
        assert(lines.size == 3)
        assert(lines.forall(!_.contains("(x")))
    }

    "pushFrame skips the shared internal placeholder frame" in {
        // Frame.internal is the shared placeholder; pushFrame drops it via a reference check, so it
        // never enters the ring and never reaches a render. The user frame is the only one that lands.
        val safepoint = Safepoint.get
        val frame     = summon[Frame]
        safepoint.pushFrame(Frame.internal)
        safepoint.pushFrame(frame)
        safepoint.pushFrame(Frame.internal)
        val snapshot = safepoint.saveTrace()
        val rendered = Trace.render(snapshot)
        // The internal placeholder is the only frame whose className is "<internal>"; if pushFrame had
        // admitted it, a render line would carry that token. It must not, regardless of ring history.
        assert(!rendered.contains("<internal>"))
        assert(rendered.contains(" @ kyo.kernel.internal.TraceTest."))
    }

    "withTrace writes the advanced index back into the trace so frames pushed during the run survive" in {
        // withTrace aliases the owner's ring to the passed Trace, runs the body, then in its finally copies
        // the advanced index back into that Trace. Without the writeback the frames land in trace.frames but
        // trace.index stays at the entry value, so a later render reads nothing. Drive withTrace over a fresh,
        // initially-empty Trace, push frames inside, and confirm the index advanced (render non-empty) after.
        val safepoint = Safepoint.get
        val trace     = Trace.init
        assert(trace.index == 0)
        assert(Trace.render(trace) == "")
        safepoint.withTrace(trace) {
            safepoint.pushFrame(summon[Frame])
            safepoint.pushFrame(summon[Frame])
        }
        assert(trace.index > 0)
        val rendered = Trace.render(trace)
        assert(rendered.nonEmpty)
        assert(rendered.startsWith("at "))
        assert(rendered.contains(" @ kyo.kernel.internal.TraceTest."))
    }

    "withTrace folds the written-back index into [0, 2*maxTraceFrames) so a long-lived fiber cannot overflow" in {
        // The write-back counts every frame pushed over the trace's whole life, so across many batches a
        // raw count would climb without bound (toward Int.MaxValue, where it overflows to a negative index
        // that crashes saveTrace/TracePool.clear and empties every render). Drive many withTrace batches
        // over one long-lived Trace, each pushing frames well past the maxTraceFrames boundary, and confirm
        // the stored index stays non-negative and bounded throughout, while render still surfaces frames.
        val safepoint = Safepoint.get
        val trace     = Trace.init
        val frame     = summon[Frame]
        @scala.annotation.tailrec
        def batches(n: Int): Unit =
            if n > 0 then
                safepoint.withTrace(trace) {
                    safepoint.pushFrame(frame)
                    safepoint.pushFrame(frame)
                }
                // Without the fold the stored index would equal the cumulative push count (3 per batch),
                // crossing 2*maxTraceFrames within ~11 batches; the fold keeps it in [0, 2*maxTraceFrames).
                assert(trace.index >= 0)
                assert(trace.index < 2 * maxTraceFrames)
                batches(n - 1)
        batches(500)
        // After hundreds of batches the ring is long full, so the folded index has settled into the upper
        // half of the canonical range, never the unbounded count a raw write-back would have stored.
        assert(trace.index >= maxTraceFrames)
        assert(trace.index < 2 * maxTraceFrames)
        val rendered = Trace.render(trace)
        assert(rendered.nonEmpty)
        assert(rendered.startsWith("at "))
        assert(rendered.contains(" @ kyo.kernel.internal.TraceTest."))
    }

    "the folded canonical index is output-preserving against render and always in range" in {
        // The fold maps a raw index to a canonical one in [0, 2*maxTraceFrames). It must be byte-identical
        // to the raw index through every consumer (which read only the ring-full predicate `index <
        // maxTraceFrames` and the slot mask `index & (maxTraceFrames - 1)`) and must never go negative or
        // out of range, for any input including the values a long-lived fiber would reach.
        def fold(rawIndex: Int): Int =
            if rawIndex < maxTraceFrames then rawIndex
            else maxTraceFrames + (rawIndex & (maxTraceFrames - 1))

        // A full ring of distinct, non-repeating frames so the render order depends on the start slot
        // (`index & (maxTraceFrames - 1)`): a fold that broke the mask would reorder and diverge here.
        val a = summon[Frame]
        val b = summon[Frame]
        val c = summon[Frame]
        assert(a != b && b != c && a != c)
        val frames = Array.tabulate(maxTraceFrames) { i =>
            (i % 3) match
                case 0 => a
                case 1 => b
                case _ => c
        }

        val rawIndices = List(0, 1, 8, 15, 16, 17, 31, 32, 33, 47, 48, 100, 1000, Int.MaxValue)
        rawIndices.foreach { raw =>
            val folded = fold(raw)
            // In range and non-negative for every input, including Int.MaxValue.
            assert(folded >= 0, s"fold($raw) = $folded must be non-negative")
            assert(folded < 2 * maxTraceFrames, s"fold($raw) = $folded must be < ${2 * maxTraceFrames}")
            // The fold preserves the two quantities every consumer reads off the index.
            assert((folded < maxTraceFrames) == (raw < maxTraceFrames))
            assert((folded & (maxTraceFrames - 1)) == (raw & (maxTraceFrames - 1)))
            // Byte-identical render for the raw and the folded index over the same frames.
            val rawRender    = Trace.render(new Trace(frames, raw))
            val foldedRender = Trace.render(new Trace(frames, folded))
            assert(rawRender == foldedRender, s"render diverged for raw=$raw folded=$folded")
        }

        // The full-ring renders are non-trivial: a full distinct ring yields maxTraceFrames lines.
        val full = Trace.render(new Trace(frames, fold(maxTraceFrames)))
        assert(full.linesIterator.size == maxTraceFrames)
    }

    def assertTrace[A](f: => A, expected: String)(using kyo.test.AssertScope) =
        try
            f
            fail()
        catch
            case ex: Throwable =>
                val stringWriter = new StringWriter()
                val printWriter  = new PrintWriter(stringWriter)
                ex.printStackTrace(printWriter)
                printWriter.flush()
                val full   = stringWriter.toString
                val top    = full.linesIterator.takeWhile(!_.contains("@")).toList
                val bottom = full.linesIterator.drop(top.length).takeWhile(_.contains("@")).toList
                val trace  = (top.mkString("\n") + "\n" + bottom.mkString("\n")).trim()
                assert(trace == expected.stripMargin.trim.replace("\r", ""))

end TraceTest
