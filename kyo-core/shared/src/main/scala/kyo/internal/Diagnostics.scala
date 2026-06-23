package kyo.internal

/** Process-global registry of best-effort runtime state dumpers.
  *
  * A runtime component (a transport poller, a scheduler, a connection pool) registers a named thunk that renders a snapshot of its live
  * internal state. A diagnostic consumer (the kyo-test runner when a leaf hangs, a signal handler, an admin endpoint) calls [[dumpAll]] to
  * collect every registered snapshot at once.
  *
  * The point is to make a rare hang diagnosable on its FIRST occurrence: the state is captured at the moment the consumer asks, so a
  * once-in-a-thousand-runs deadlock yields the poller's pending-fd map and liveness counters without needing a second, instrumented
  * reproduction.
  *
  * This lives in kyo-core so production components can register without depending on the test framework; the test runner (which has kyo-core
  * on its classpath) consumes it. The dumpers run on the consumer's thread, concurrently with the components they inspect, so each must read
  * its state best-effort (a stale or partially-updated snapshot is acceptable, a throw is contained by [[dumpAll]]) and must never block.
  *
  * `synchronized` guards only the registry list (a no-op on single-threaded JS); the dumper thunks run outside the lock so a slow dumper
  * cannot stall registration or other dumpers.
  */
object Diagnostics:

    /** A live registration. Closing it removes the dumper, so a component that registered at construction unregisters at close and its state
      * stops appearing in [[dumpAll]] (and cannot leak across a long-lived process).
      */
    final class Registration private[Diagnostics] (private val entry: AnyRef):
        def close(): Unit = remove(entry)

    final private case class Entry(name: String, dump: () => String)

    private var entries: List[Entry] = Nil

    /** Register a named state dumper. Returns a [[Registration]] whose `close()` removes it. */
    def register(name: String)(dump: () => String): Registration =
        val entry = Entry(name, dump)
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

end Diagnostics
