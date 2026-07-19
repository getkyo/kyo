package kyo.test

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kyo.Absent
import kyo.Frame
import kyo.Maybe

/** The allocation-counter surface [[AllocationProbe]] measures through. Narrows
  * `com.sun.management.ThreadMXBean` to the four members the probe uses, so a test can inject a
  * counter reporting unsupported or disabled without touching the real HotSpot bean.
  */
private[kyo] trait AllocationCounter:
    def isSupported: Boolean
    def isEnabled: Boolean
    def enable(): Unit
    def currentThreadAllocatedBytes(): Long
end AllocationCounter

/** JVM-only allocation probe: measures the per-op heap allocation of a REAL decode/observe callback
  * and fails the calling leaf when it exceeds a per-op byte bound.
  *
  * Backed by `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes`, a HotSpot extension the
  * base `java.lang.management.ThreadMXBean` does not carry (hence the narrowing cast of the platform
  * bean). The current-thread form needs no thread id, so nothing on this path is newer than JDK 14 and
  * the file compiles at the project's Java 17 release floor.
  *
  * The measured `op` MUST exercise a genuine decode/observe path (a real per-OS decode callback
  * running against a staged file or binding image), never an identity or length-only callback: the
  * whole point is to fail when the true per-op allocation regresses, and a callback that excludes the
  * allocating stages measures nothing.
  *
  * Failures are reported through the CALLING LEAF's `AssertScope`, exactly as `kyo.test.internal.Intercept`
  * does: one evaluation is recorded on entry (so a leaf whose only check is a probe still satisfies
  * `failOnNoAssertion`), and a violation records an `AssertionFailed` on the leaf's scope and then throws
  * it, so a failure raised on a detached fiber still reaches the leaf.
  */
object AllocationProbe:

    // Unsafe: the per-thread allocation counter is a HotSpot extension absent from the base
    // java.lang.management.ThreadMXBean, so the platform bean is narrowed to the interface that carries it.
    private val bean: ThreadMXBean =
        ManagementFactory.getThreadMXBean.asInstanceOf[ThreadMXBean]

    private val hotSpotCounter: AllocationCounter =
        new AllocationCounter:
            def isSupported: Boolean                = bean.isThreadAllocatedMemorySupported
            def isEnabled: Boolean                  = bean.isThreadAllocatedMemoryEnabled
            def enable(): Unit                      = bean.setThreadAllocatedMemoryEnabled(true)
            def currentThreadAllocatedBytes(): Long = bean.getCurrentThreadAllocatedBytes()

    /** Independent post-warmup measurement windows the bound is checked against the MINIMUM of. A JIT
      * background-compile finishing partway through a measurement window (a live race under CPU
      * contention, not something warmup alone can rule out) attributes its one-off bookkeeping to at most
      * one window; a genuine per-op allocation is deterministic and shows up in every window, so taking
      * the minimum filters the former without hiding the latter.
      */
    private val trials = 5

    /** Runs `op` `warmupIters` times to warm the JIT, then measures thread-allocated bytes across several
      * independent windows of `measuredIters` further runs each and requires the best-of-[[trials]]
      * `(after - before) / measuredIters <= maxBytesPerOp`. When the allocation counter is unsupported or
      * cannot be enabled the probe FAILS LOUD rather than passing silently: an unmeasurable claim is not a
      * satisfied one.
      */
    def assertBoundedPerOp[A](warmupIters: Int, measuredIters: Int, maxBytesPerOp: Double)(
        op: => A
    )(using Frame, AssertScope): Unit =
        assertBoundedPerOp(hotSpotCounter, warmupIters, measuredIters, maxBytesPerOp)(op)

    /** Test seam: the same measurement, routed through an injected [[AllocationCounter]] instead of the
      * real HotSpot bean. The real bean always reports its counter supported, so the unsupported branch
      * above is unreachable through the public overload; this seam is what lets that branch be exercised
      * by a named test rather than staying an asserted-but-unverified safety claim.
      */
    private[kyo] def assertBoundedPerOp[A](counter: AllocationCounter, warmupIters: Int, measuredIters: Int, maxBytesPerOp: Double)(
        op: => A
    )(using frame: Frame, scope: AssertScope): Unit =
        scope.recordEvaluated()
        if !counter.isSupported then
            violation(
                "per-thread allocation measurement is unsupported on this JVM, so the allocation bound cannot be checked"
            )
        else
            if !counter.isEnabled then counter.enable()
            iterate(warmupIters, op)
            val minPerOp = minPerOpAcrossTrials(counter, op, measuredIters)
            if minPerOp > maxBytesPerOp then
                violation(
                    s"per-op allocation $minPerOp bytes exceeds the bound $maxBytesPerOp (measured over $measuredIters ops)"
                )
            end if
        end if
    end assertBoundedPerOp

    private def violation(msg: String)(using frame: Frame, scope: AssertScope): Nothing =
        val failure = new AssertionFailed(msg, frame, Maybe(msg), Absent)
        scope.record(failure)
        throw failure
    end violation

    private def iterate[A](n: Int, op: => A): Unit =
        @scala.annotation.tailrec
        def loop(i: Int): Unit =
            if i < n then
                val _ = op
                loop(i + 1)
        loop(0)
    end iterate

    /** Runs [[trials]] independent post-warmup windows of `measuredIters` calls each and returns the
      * minimum measured `(after - before) / measuredIters`, per [[trials]]'s rationale.
      */
    private def minPerOpAcrossTrials[A](counter: AllocationCounter, op: => A, measuredIters: Int): Double =
        @scala.annotation.tailrec
        def loop(trial: Int, minPerOp: Double): Double =
            if trial >= trials then minPerOp
            else
                val before = counter.currentThreadAllocatedBytes()
                iterate(measuredIters, op)
                val after = counter.currentThreadAllocatedBytes()
                val perOp = (after - before).toDouble / measuredIters
                loop(trial + 1, math.min(minPerOp, perOp))
        loop(0, Double.MaxValue)
    end minPerOpAcrossTrials

end AllocationProbe
