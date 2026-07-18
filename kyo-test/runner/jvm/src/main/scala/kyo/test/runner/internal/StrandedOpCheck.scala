package kyo.test.runner.internal

import java.util.concurrent.locks.LockSupport
import kyo.Chunk
import kyo.Maybe
import kyo.internal.Diagnostics

/** JVM end-of-run stranded-op probe, read at the sbt `done()` boundary right after [[LeakCheck]], once the fork is quiescent.
  *
  * Promotes the per-driver [[Diagnostics]] snapshot kyo-net's drivers register (a loop-cycle counter plus a pending-work flag: a queued
  * command, an armed read/write/accept, an unreaped completion) from a dump a human reads on a hung leaf into an automatic classifier
  * that runs on EVERY suite. The governing principle: a loop whose cycle counter
  * advances is making continuous progress, however long it then takes, and is never a finding; a loop whose pending work survives
  * unchanged while its cycle counter stays frozen has parked with nothing left to wake it, which is a lost wakeup. [[detect]] takes two
  * [[Diagnostics.probeAll]] samples a settle window apart and applies exactly that rule to each registered component.
  *
  * Unlike [[LeakCheck]] this check has no per-suite opt-out: a stranded op is never acceptable suite behavior, so every forked run is
  * checked. It reuses [[LeakCheck.defaultAllowlist]] and the fork's aggregated `leakCheckAllowlist` (the same plumbing LeakCheck itself
  * uses to excuse the process-shared singleton) rather than a second allowlist: kyo-net's drivers tag that by-design, never-closed
  * singleton's own [[Diagnostics]] registration name with the same `processSharedTransport` marker LeakCheck's default allowlist already
  * matches, so the one component that is SUPPOSED to sit parked with pending work forever (an idle kept-alive connection) is exempt by
  * the same convention as every other process-lifetime resource, not a second one.
  */
private[runner] object StrandedOpCheck:

    /** One component's verdict against a pair of samples. [[Stranded]] carries the frozen cycle count for the report. */
    private enum Verdict derives CanEqual:
        case Ok
        case Stranded(cycles: Long)

    /** Classifies one component's `before`/`after` [[Diagnostics.Probe]] pair. `pending` is required in BOTH samples (not just one):
      * work that was pending and drained during the window resolved on its own (not a finding), and requiring it present at both ends
      * is the conservative reading of "a queue-or-pending-op survives the window" that a first-landing, always-on, hard-failing gate
      * should take. `cycles` differing between the two samples is continuous progress and is never a finding, however slow.
      */
    private def classify(name: String, allowlist: Chunk[String], before: Diagnostics.Probe, after: Diagnostics.Probe): Verdict =
        if allowlist.exists(name.contains) then Verdict.Ok
        else if after.closed then Verdict.Ok
        else if !before.pending || !after.pending then Verdict.Ok
        else if before.cycles != after.cycles then Verdict.Ok
        else Verdict.Stranded(after.cycles)

    /** Samples every registered [[Diagnostics.Probe]] twice, `settleNanos` apart, and classifies each by name against `allowlist` (in
      * addition to [[LeakCheck.defaultAllowlist]]). Returns one report line per stranded component (name, frozen cycle count), or
      * `Absent` when none is found. A component present in only one of the two samples (registered or unregistered mid-window) is
      * skipped: comparing requires both ends.
      */
    def detect(allowlist: Chunk[String], settleNanos: Long): Maybe[String] =
        val before = Diagnostics.probeAll().toMap
        LockSupport.parkNanos(settleNanos)
        val after              = Diagnostics.probeAll().toMap
        val effectiveAllowlist = LeakCheck.defaultAllowlist ++ allowlist
        val findings           = Chunk.newBuilder[String]
        after.foreach { (name, afterProbe) =>
            before.get(name).foreach { beforeProbe =>
                classify(name, effectiveAllowlist, beforeProbe, afterProbe) match
                    case Verdict.Stranded(cycles) =>
                        findings += s"$name: pending op present with cycles frozen at $cycles across the settle window (lost wakeup)"
                    case Verdict.Ok => ()
            }
        }
        val all = findings.result()
        if all.isEmpty then Maybe.empty
        else Maybe(all.mkString("\n  - ", "\n  - ", "") + "\n\nfull diagnostics dump:\n" + Diagnostics.dumpAll())
    end detect

    /** Thrown from the forked runner's `done()` when [[detect]] finds a stranded op. Failing by exception is what marks the forked test
      * task failed; sbt surfaces the message as a `ForkMain$ForkError`.
      */
    final class Detected(report: String)
        extends RuntimeException(
            s"kyo-test stranded-op check failed:$report\n\nA component's pending work (a queued command, an armed read/write/accept, " +
                "an unreaped completion) survived two samples with its loop-cycle counter frozen: the loop parked and nothing woke it " +
                "(a lost wakeup), not a slow test (a live loop's cycle counter keeps advancing, however long the work takes). The full " +
                "diagnostics dump above names every registered component's live state for attribution."
        )

end StrandedOpCheck
