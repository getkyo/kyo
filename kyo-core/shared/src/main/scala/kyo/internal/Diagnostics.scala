package kyo.internal

/** Process-global registry of best-effort runtime state dumpers.
  *
  * A runtime component (a transport poller, a scheduler, a connection pool) registers a named thunk that renders a snapshot of its live
  * internal state, and optionally a second thunk yielding a machine-checkable [[Probe]] of the same state. A diagnostic consumer (the
  * kyo-test runner when a leaf hangs, a signal handler, an admin endpoint) calls [[dumpAll]] for the human-readable snapshot or
  * [[probeAll]] for the checkable one.
  *
  * The point is to make a rare hang diagnosable on its FIRST occurrence: the state is captured at the moment the consumer asks, so a
  * once-in-a-thousand-runs deadlock yields the poller's pending-fd map and liveness counters without needing a second, instrumented
  * reproduction. [[probeAll]] goes further: it lets a consumer turn that same state into an automatic pass/fail verdict (see the
  * stranded-op post-suite gate in kyo-test's runner) rather than relying on a human reading the dump.
  *
  * This lives in kyo-core so production components can register without depending on the test framework; the test runner (which has kyo-core
  * on its classpath) consumes it. The dumpers and probes run on the consumer's thread, concurrently with the components they inspect, so
  * each must read its state best-effort (a stale or partially-updated snapshot is acceptable, a throw is contained by [[dumpAll]] /
  * [[probeAll]]) and must never block.
  *
  * `synchronized` guards only the registry list (a no-op on single-threaded JS); the dumper/probe thunks run outside the lock so a slow one
  * cannot stall registration or the others.
  */
private[kyo] object Diagnostics:

    /** A live registration. Closing it removes the dumper (and its probe, if any), so a component that registered at construction
      * unregisters at close and its state stops appearing in [[dumpAll]] / [[probeAll]] (and cannot leak across a long-lived process).
      */
    final class Registration private[Diagnostics] (private val entry: AnyRef):
        def close(): Unit = remove(entry)

    /** A machine-checkable snapshot of one component's transient work state, taken alongside the human-readable dump. `closed` is the
      * component's own closed/shutdown flag; `cycles` is a monotonic counter of loop turns (poll cycles, reap cycles, ...) that advances
      * only when the component's own loop actually runs a turn; `pending` is true when the component holds outstanding work (a queued
      * command, an in-flight fd wait) that only its own loop can drain.
      *
      * A single [[Probe]] says nothing on its own: a healthy loop legitimately has `pending = true` with unchanged `cycles` between two
      * probes taken back-to-back. The signal is in comparing two probes of the SAME component a settle window apart: `pending` staying
      * true while `cycles` stays frozen across that window, on a component that is not `closed`, means the loop parked and nothing woke
      * it (a lost wakeup). `cycles` advancing between the two probes is continuous progress and never a finding, however long the loop
      * then takes. See the stranded-op classifier that consumes [[probeAll]] in kyo-test's runner.
      */
    final case class Probe(closed: Boolean, cycles: Long, pending: Boolean) derives CanEqual

    /** The probe reported by a component that registered no [[Probe]] thunk: `closed = true` so it can never contribute a stranded-op
      * finding (there is nothing to classify).
      */
    private val noProbe: () => Probe = () => Probe(closed = true, cycles = 0L, pending = false)

    final private case class Entry(name: String, dump: () => String, probe: () => Probe)

    private var entries: List[Entry] = Nil

    /** Register a named state dumper, and optionally a [[Probe]] thunk for the stranded-op classifier. A component with no probe (the
      * default) is reported via [[noProbe]] and never flagged. Returns a [[Registration]] whose `close()` removes both.
      */
    def register(name: String)(dump: () => String, probe: () => Probe = noProbe): Registration =
        val entry = Entry(name, dump, probe)
        synchronized { entries = entry :: entries }
        new Registration(entry)
    end register

    private def remove(entry: AnyRef): Unit =
        synchronized { entries = entries.filterNot(_ eq entry) }

    /** Render every registered dumper's snapshot, each under its name. A dumper that throws is reported inline rather than aborting the rest,
      * so one broken component never suppresses the others' state.
      */
    def dumpAll(): String =
        val snapshot = synchronized { entries }
        if snapshot.isEmpty then "Diagnostics: (no dumpers registered)"
        else
            snapshot.reverse.map { e =>
                val body =
                    try e.dump()
                    catch case t: Throwable => "dump threw: " + t
                "=== " + e.name + " ===\n" + body
            }.mkString("\n")
        end if
    end dumpAll

    /** Every registered component's current [[Probe]], alongside its name. A probe that throws is dropped, exactly as a throwing dump is
      * caught inline in [[dumpAll]]: one broken component's probe must never blind the classifier to every other component.
      */
    def probeAll(): List[(String, Probe)] =
        val snapshot = synchronized { entries }
        snapshot.reverse.flatMap { e =>
            try List(e.name -> e.probe())
            catch case _: Throwable => Nil
        }
    end probeAll

end Diagnostics
