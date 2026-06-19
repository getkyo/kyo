package kyo.test.runner.internal

import com.sun.management.UnixOperatingSystemMXBean
import java.lang.management.ManagementFactory
import java.util.concurrent.locks.LockSupport
import kyo.Chunk
import kyo.Maybe
import kyo.scheduler.Scheduler
import scala.jdk.CollectionConverters.*

/** JVM end-of-run leak probes, read at the sbt `done()` boundary after every suite in the fork has finished.
  *
  * Three process-global resources are sampled, never per-leaf: the OS file-descriptor table, the kyo scheduler, and the JVM's non-daemon
  * threads. At `done()` the fork is quiescent (all leaves joined), so a descriptor still open, a fiber still runnable, or a non-daemon thread
  * still alive is one a leaf failed to release. The probes only read existing surfaces (`UnixOperatingSystemMXBean`, `Scheduler.get`,
  * `Thread.getAllStackTraces`); nothing in the scheduler or core changes.
  *
  * Detectable: descriptor leaks; runnable/spinning fiber leaks (a fiber pegging or repeatedly rescheduling onto a worker, the class the
  * async-merge spinning-producer bug produced); and non-daemon thread leaks (a raw `Thread` or un-shutdown executor that keeps the JVM from
  * exiting cleanly; the scheduler's own threads are daemons, so they never trip this). Not detectable here: a fiber parked on a
  * still-reachable promise/channel is off-scheduler and invisible to scheduler status; catching that would need a core registry.
  */
private[runner] object LeakCheck:

    /** Default descriptor-growth tolerance for [[detect]], overridable with `-Dkyo.test.leakCheck.fdTolerance`. Absorbs the bounded,
      * one-time growth of classloader-held jar handles opened lazily as a fork loads suite classes, so that natural growth is not reported as
      * a leak while gross descriptor accumulation (a socket or file per iteration) still trips the check.
      */
    val defaultFdTolerance: Long = 128L

    /** Current open file-descriptor count, or `Absent` on a JVM/OS without the Unix OS MXBean (e.g. Windows HotSpot). */
    def openFdCount(): Maybe[Long] =
        ManagementFactory.getOperatingSystemMXBean match
            case os: UnixOperatingSystemMXBean => Maybe(os.getOpenFileDescriptorCount)
            case _                             => Maybe.empty

    /** Average scheduler load across active workers: queued plus executing tasks per worker. `0.0` when fully idle. */
    def loadAvg(): Double = Scheduler.get.loadAvg()

    /** Snapshot of currently-live non-daemon threads, by identity. Captured as a baseline at runner construction (so the JVM's own infra
      * threads ; `main`, the sbt ForkMain reader ; are excluded), then diffed at `done()`.
      */
    def liveNonDaemonThreads(): Set[Thread] =
        Thread.getAllStackTraces.keySet.asScala.iterator.filter(t => t.isAlive && !t.isDaemon).toSet

    // sbt runs each suite task on a thread from its own ForkMain executor (`pool-N-thread-M`), which is non-daemon and stays
    // parked between tasks; at `done()` those idle harness threads would look identical to a leaked test thread. They are
    // identified structurally rather than by name: every `execute()` runs ON one of them, so registering the carrier thread at
    // the top of each suite execution records exactly sbt's pool, with no pattern that a real test thread pool could collide
    // with. Process-global because there is one fork JVM; the caller only registers when leak detection is active (forked), so
    // it never accumulates in the long-lived main sbt JVM.
    private val carrierThreads: java.util.Set[Thread] =
        java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap[Thread, java.lang.Boolean]())

    /** Records the calling thread as sbt harness infrastructure, excluding it from [[leakedNonDaemonThreads]]. Called at the top of each
      * suite execution, where the calling thread is the sbt ForkMain pool thread carrying that task.
      */
    def registerCarrierThread(): Unit =
        carrierThreads.add(Thread.currentThread): Unit

    /** Live non-daemon threads not present in `baseline`, not a registered sbt carrier thread, and not the calling thread: threads a test
      * started and left running, which a raw `Thread` or an un-shutdown executor produces and which block a clean JVM exit. Each entry is the
      * thread name plus its top stack frame.
      */
    def leakedNonDaemonThreads(baseline: Set[Thread]): Chunk[String] =
        val self = Thread.currentThread
        val out  = Chunk.newBuilder[String]
        Thread.getAllStackTraces.asScala.foreach { case (t, st) =>
            if t.isAlive && !t.isDaemon && (t ne self) && !baseline.contains(t) && !carrierThreads.contains(t) then
                val top = if st.nonEmpty then st(0).toString else "<no frame>"
                out += s"${t.getName} @ $top"
        }
        out.result()
    end leakedNonDaemonThreads

    /** A stack frame of a worker that currently holds work (`load > 0`), if any: identifies where a still-running fiber is executing. Reads
      * the full status (captures worker stack traces), so call it only when reporting, not in a tight poll.
      */
    def busyWorkerFrame(): Maybe[String] =
        val workers            = Scheduler.get.status().workers
        var i                  = 0
        var res: Maybe[String] = Maybe.empty
        while i < workers.length && res.isEmpty do
            val w = workers(i)
            if (w ne null) && w.load > 0 && (w.frame ne null) then res = Maybe(w.frame)
            i += 1
        end while
        res
    end busyWorkerFrame

    /** Outcome of [[awaitSchedulerIdle]]. */
    enum IdleResult derives CanEqual:
        case Idle
        case Busy(loadAvg: Double, frame: Maybe[String])

    /** Polls the scheduler until its load has been `0.0` continuously for `settleNanos`, or until `budgetNanos` elapses.
      *
      * The settle window lets transient tail activity (a reporter fiber, a finalizer) drain before a verdict, so only work that persists past
      * the budget is reported as `Busy`. Blocking by design: called at the sbt `done()` boundary, outside any fiber, so parking the caller is
      * correct here rather than an `Async` suspension.
      */
    def awaitSchedulerIdle(budgetNanos: Long, settleNanos: Long, pollNanos: Long): IdleResult =
        val deadline                  = System.nanoTime() + budgetNanos
        var idleSince: Long           = -1L
        var result: Maybe[IdleResult] = Maybe.empty
        while result.isEmpty && System.nanoTime() < deadline do
            val now = System.nanoTime()
            if loadAvg() == 0.0 then
                if idleSince < 0 then idleSince = now
                else if now - idleSince >= settleNanos then result = Maybe(IdleResult.Idle)
            else idleSince = -1L
            end if
            if result.isEmpty then LockSupport.parkNanos(pollNanos)
        end while
        result.getOrElse {
            if loadAvg() == 0.0 then IdleResult.Idle
            else IdleResult.Busy(loadAvg(), busyWorkerFrame())
        }
    end awaitSchedulerIdle

    /** True when running inside an sbt forked test JVM (`sbt.ForkMain`), the only JVM where end-of-run leak detection is both sound (the fork
      * holds only this run's resources) and safe to fail by exit (failing the main sbt JVM would take sbt down). Two agreeing signals: the
      * `sun.java.command` property and an `sbt.ForkMain` frame on the `main` thread. Defaults to `false` on any ambiguity, because the verdict
      * gates an irreversible JVM-failing action.
      */
    def isForked: Boolean =
        Option(System.getProperty("sun.java.command")).exists(_.startsWith("sbt.ForkMain")) ||
            Thread.getAllStackTraces.asScala.exists { (t, st) =>
                (t.getName == "main") && st.exists(_.getClassName.startsWith("sbt.ForkMain"))
            }

    /** Process-global resource snapshot taken once at runner construction, before any suite runs, and diffed at `done()`. Captures the open
      * descriptor count and the set of live non-daemon threads so the JVM's own startup infrastructure (the `main` thread, the ForkMain
      * reader) is excluded from the diff.
      */
    final case class Baseline(fd: Maybe[Long], threads: Set[Thread])

    /** Captures a [[Baseline]] of the current open descriptors and live non-daemon threads. */
    def baseline(): Baseline = Baseline(openFdCount(), liveNonDaemonThreads())

    /** Runs the three end-of-run probes against `baseline` and returns a leak report, or `Absent` when the fork is clean.
      *
      * Order: the scheduler/fiber probe first (it owns the settle window), then a `System.gc()` plus settle so Cleaner-closed abandoned
      * channels and finished threads drop out before the descriptor and thread diffs (a genuine leak stays referenced and survives the gc, so
      * this trims false positives without hiding real leaks). The descriptor delta is compared against `fdTolerance`, which absorbs the
      * bounded growth of classloader-held jar handles opened lazily as suites load classes.
      */
    def detect(
        baseline: Baseline,
        fdTolerance: Long,
        idleBudgetNanos: Long,
        settleNanos: Long,
        pollNanos: Long
    ): Maybe[String] =
        val findings = Chunk.newBuilder[String]

        awaitSchedulerIdle(idleBudgetNanos, settleNanos, pollNanos) match
            case IdleResult.Idle => ()
            case IdleResult.Busy(la, frame) =>
                findings += s"fiber leak: scheduler still busy (loadAvg=$la) after settle; running at ${frame.getOrElse("<unknown frame>")}"
        end match

        System.gc()
        LockSupport.parkNanos(settleNanos)

        val threadLeaks = leakedNonDaemonThreads(baseline.threads)
        if threadLeaks.nonEmpty then
            findings += s"non-daemon thread leak (${threadLeaks.size}): ${threadLeaks.mkString("; ")}"

        (baseline.fd, openFdCount()) match
            case (Maybe.Present(before), Maybe.Present(after)) =>
                val delta = after - before
                if delta > fdTolerance then
                    findings += s"file-descriptor leak: open fds grew by $delta (before=$before after=$after, tolerance=$fdTolerance)"
            case _ => () // OS MXBean unavailable: descriptor probe is a no-op on this platform.
        end match

        val all = findings.result()
        if all.isEmpty then Maybe.empty
        else Maybe(all.mkString("\n  - ", "\n  - ", ""))
    end detect

    /** Thrown from the forked runner's `done()` when [[detect]] finds a leak. Failing by exception is what marks the forked test task failed;
      * sbt surfaces the message as a `ForkMain$ForkError`.
      */
    final class Detected(report: String)
        extends RuntimeException(
            s"kyo-test leak check failed:$report\n\nThese resources outlived the test run; a leaked fiber, thread, or descriptor means a " +
                "test (or the code under test) did not release a resource. Disable with -Dkyo.test.leakCheck=false."
        )

end LeakCheck
