package kyo.test.runner.internal

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.locks.LockSupport
import kyo.Chunk
import kyo.Maybe
import kyo.scheduler.Scheduler
import scala.jdk.CollectionConverters.*

/** JVM end-of-run leak probes, read at the sbt `done()` boundary after every suite in the fork has finished.
  *
  * Three process-global resources are sampled, never per-leaf: the open file descriptors, the kyo scheduler, and the JVM's non-daemon
  * threads. At `done()` the fork is quiescent (all leaves joined), so a descriptor still open, a fiber still runnable, or a non-daemon thread
  * still alive is one a leaf failed to release. The probes only read existing surfaces (`/proc/self/fd`, `Scheduler.get`,
  * `Thread.getAllStackTraces`); nothing in the scheduler or core changes.
  *
  * Detectable: descriptor leaks (a socket, pipe, or file open at `done()` that was not open at construction and is not a classpath jar or JVM
  * internal; identified precisely by enumerating and reading the `/proc/self/fd` symlinks, so there is no count tolerance); runnable/spinning
  * fiber leaks (a fiber pegging or repeatedly rescheduling onto a worker, the class the async-merge spinning-producer bug produced); and
  * non-daemon thread leaks (a raw `Thread` or un-shutdown executor that keeps the JVM from exiting cleanly; the scheduler's own threads are
  * daemons, so they never trip this). Not detectable here: a fiber parked on a still-reachable promise/channel is off-scheduler and invisible
  * to scheduler status; catching that would need a core registry. The descriptor probe is Linux-only (`/proc/self/fd`); a no-op elsewhere.
  */
private[runner] object LeakCheck:

    /** Built-in allowlist patterns applied by [[detect]] in addition to each suite's `RunConfig.leakCheckAllowlist`, for process-lifetime infra
      * that legitimately outlives every test in the fork.
      *
      * `NioIoDriver` is allowlisted pending the network stack rewrite. kyo-http's process-wide IO transport (`HttpPlatformTransport.transport`)
      * is a lazy singleton whose `NioIoDriver` runs a selector event loop on a scheduler fiber for the JVM's lifetime, and nothing ever closes
      * the shared transport, so the fiber is parked in `select()` at every http-using module's end-of-run check. It is intentional infra, not a
      * leak; excusing it here covers every http-touching test module in one place. The entry is removed once the network stack gives the driver
      * a proper lifecycle (or moves its loop off the scheduler).
      */
    val defaultAllowlist: Chunk[String] = Chunk("NioIoDriver")

    /** The set of open file descriptors, each as its `/proc/self/fd` symlink target (`socket:[inode]`, `pipe:[inode]`, a file path, a `.jar`,
      * ...). `Absent` on a platform without `/proc/self/fd` (macOS, Windows), where the descriptor probe is a no-op. The descriptor that the
      * enumeration itself opens (the directory stream) targets `/proc/.../fd` and is filtered by [[benignFd]], so it never reads as a leak.
      */
    def openFdTargets(): Maybe[Set[String]] =
        val dir = Paths.get("/proc/self/fd")
        if !Files.isDirectory(dir) then Maybe.empty
        else
            val targets = Set.newBuilder[String]
            val stream  = Files.newDirectoryStream(dir)
            try
                stream.forEach { entry =>
                    val target =
                        try Files.readSymbolicLink(entry).toString
                        catch case _: Throwable => "<gone>" // the fd closed between listing and readlink; ignore
                    targets += target
                }
            finally stream.close()
            end try
            Maybe(targets.result())
        end if
    end openFdTargets

    /** True for a descriptor target that is legitimately open for the JVM's lifetime regardless of any test: a classpath jar, a native
      * library, a device or proc/sys pseudo-file, a JVM-internal anonymous inode (epoll, eventfd), or the runtime image. These are excluded
      * from the descriptor diff so that lazy classloading (which opens jar handles as suites load classes) is never reported as a leak.
      */
    def benignFd(target: String): Boolean =
        target.endsWith(".jar") || target.contains(".so") ||
            target.startsWith("/dev/") || target.startsWith("/proc/") || target.startsWith("/sys/") ||
            target.startsWith("anon_inode:") || target.startsWith("/modules/") || target == "<gone>"

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

    /** Live non-daemon threads not present in `baseline`, not a registered sbt carrier thread, not the calling thread, and not allowlisted:
      * threads a test started and left running, which a raw `Thread` or an un-shutdown executor produces and which block a clean JVM exit. A
      * thread is allowlisted if any pattern appears in its name or any of its stack frames. Each entry is the thread name plus its top frame.
      */
    def leakedNonDaemonThreads(baseline: Set[Thread], allowlist: Chunk[String]): Chunk[String] =
        val self = Thread.currentThread
        val out  = Chunk.newBuilder[String]
        Thread.getAllStackTraces.asScala.foreach { case (t, st) =>
            if t.isAlive && !t.isDaemon && (t ne self) && !baseline.contains(t) && !carrierThreads.contains(t) then
                val allowlisted = allowlist.exists(p => t.getName.contains(p) || st.exists(_.toString.contains(p)))
                if !allowlisted then
                    // Report the thread's state and full stack, not just the top frame: a leaked non-daemon thread blocks a clean
                    // JVM exit, and the stack (what it is parked on or looping in) is what a CI reader needs to trace it back to the
                    // test that started it. The top frame alone is usually an opaque park/wait.
                    val stack = if st.nonEmpty then st.iterator.take(30).map(f => s"        at $f").mkString("\n") else "        <no frame>"
                    out += s"${t.getName} (${t.getState})\n$stack"
                end if
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

    /** The full stack of a worker that currently holds work (`load > 0`), joined into one string, for allowlist matching. The scheduler's
      * `WorkerStatus.frame` is only the top frame, and the top frame of a blocked fiber is an OS-specific syscall (`EPoll.wait` on Linux,
      * `KQueue` on macOS); a allowlist needs a stable kyo frame deeper in the stack, so this reads the worker's mount thread's full stack via
      * `Thread.getAllStackTraces` (keyed by the mount thread name in the status). `Absent` when no worker is busy.
      */
    def busyWorkerStack(): Maybe[String] =
        val workers              = Scheduler.get.status().workers
        var i                    = 0
        var mount: Maybe[String] = Maybe.empty
        while i < workers.length && mount.isEmpty do
            val w = workers(i)
            if (w ne null) && w.load > 0 && (w.mount ne null) && w.mount.nonEmpty then mount = Maybe(w.mount)
            i += 1
        end while
        mount.flatMap { name =>
            var res: Maybe[String] = Maybe.empty
            Thread.getAllStackTraces.asScala.foreach { case (t, st) =>
                if res.isEmpty && t.getName == name then res = Maybe(st.mkString("\n"))
            }
            res
        }
    end busyWorkerStack

    /** A thread dump of every thread that is actually doing something at probe time, for an actionable fiber-leak report: each thread whose
      * state is `RUNNABLE` or whose stack runs kyo code, with its name, state, and stack. Unlike [[busyWorkerStack]] (which sees only scheduler
      * workers) this also captures NON-worker threads, e.g. a caller stuck mid-`offer` that holds a queue's race-repair counter while a worker
      * spins in `close()` waiting for it. Idle pool/parked threads with no kyo frame are filtered out to keep the report focused. The leak
      * check's own thread is excluded.
      */
    def runningThreadsDump(): String =
        val self = Thread.currentThread()
        val sb   = new StringBuilder
        Thread.getAllStackTraces.asScala.toList
            .filter { (t, st) =>
                (t ne self) && st.nonEmpty &&
                ((t.getState eq Thread.State.RUNNABLE) || st.exists(_.getClassName.startsWith("kyo.")))
            }
            .sortBy((t, _) => t.getName)
            .foreach { (t, st) =>
                sb.append(s"\n  \"${t.getName}\" ${t.getState}\n")
                st.iterator.take(30).foreach(f => sb.append(s"    at $f\n"))
            }
        sb.toString
    end runningThreadsDump

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

    /** Descriptor targets open at `done()` that were not open at construction and are neither benign ([[benignFd]]) nor allowlisted: a socket,
      * pipe, or file a leaf opened and never closed. Diffing against the baseline excludes the fork's own startup descriptors, like the
      * `sbt.ForkMain` socket back to the main JVM, which is open the whole run.
      */
    def fdLeaks(baseline: Set[String], current: Set[String], allowlist: Chunk[String]): Chunk[String] =
        val out = Chunk.newBuilder[String]
        current.foreach { target =>
            if !baseline.contains(target) && !benignFd(target) && !allowlist.exists(target.contains) then
                out += target
        }
        out.result()
    end fdLeaks

    /** Restricts descriptor leaks to the enabled descriptor categories: a socket target (`socket:[inode]`) is kept only when `checkSockets` is
      * on, every other target (files, directories, pipes) only when `checkFileDescriptors` is on. Lets a suite exempt the socket category while
      * still detecting file-descriptor leaks (e.g. an unclosed `Files.list` directory stream).
      */
    def fdLeaksForCategories(leaks: Chunk[String], checkSockets: Boolean, checkFileDescriptors: Boolean): Chunk[String] =
        leaks.filter(target => if target.startsWith("socket:[") then checkSockets else checkFileDescriptors)

    private val tcpStates = Map(
        "01" -> "ESTABLISHED",
        "02" -> "SYN_SENT",
        "03" -> "SYN_RECV",
        "04" -> "FIN_WAIT1",
        "05" -> "FIN_WAIT2",
        "06" -> "TIME_WAIT",
        "07" -> "CLOSE",
        "08" -> "CLOSE_WAIT",
        "09" -> "LAST_ACK",
        "0A" -> "LISTEN",
        "0B" -> "CLOSING"
    )

    /** For a `socket:[inode]` target, resolves the connection's TCP state and local/remote ports from `/proc/net/tcp{,6}`, so a leaked socket
      * is actionable rather than an opaque inode: e.g. `CLOSE_WAIT` means the peer closed and this side held the connection open, and the ports
      * say which side it is (an ephemeral local port to a server's remote port is a client connection). Returns "" for a non-socket target or an
      * inode that cannot be resolved.
      */
    def describeSocket(target: String): String =
        if !target.startsWith("socket:[") then ""
        else
            val inode = target.stripPrefix("socket:[").stripSuffix("]")
            def scan(path: String): Maybe[String] =
                try
                    val lines              = java.nio.file.Files.readAllLines(Paths.get(path)).asScala
                    var res: Maybe[String] = Maybe.empty
                    lines.foreach { line =>
                        val f = line.trim.split("\\s+")
                        // columns: sl local rem st ... inode (index 9); the header row has no numeric inode at f(9)
                        if res.isEmpty && f.length > 9 && f(9) == inode then
                            val st = tcpStates.getOrElse(f(3).toUpperCase, f(3))
                            val lp = Integer.parseInt(f(1).split(":")(1), 16)
                            val rp = Integer.parseInt(f(2).split(":")(1), 16)
                            res = Maybe(s" [$st local:$lp remote:$rp]")
                        end if
                    }
                    res
                catch case _: Throwable => Maybe.empty
            scan("/proc/net/tcp").orElse(scan("/proc/net/tcp6")).getOrElse("")
    end describeSocket

    /** Process-global resource snapshot taken once at runner construction, before any suite runs, and diffed at `done()`. Captures the open
      * descriptor targets and the set of live non-daemon threads so the JVM's own startup infrastructure (the `main` thread, the ForkMain
      * reader and socket) is excluded from the diff.
      */
    final case class Baseline(fds: Maybe[Set[String]], threads: Set[Thread])

    /** Captures a [[Baseline]] of the current open descriptors and live non-daemon threads. */
    def baseline(): Baseline = Baseline(openFdTargets(), liveNonDaemonThreads())

    /** Runs the enabled end-of-run probes against `baseline`, excusing any finding matched by `allowlist`, and returns a leak report or `Absent`
      * when the fork is clean. The four `check*` flags gate the categories independently (a suite can exempt just sockets, say, and still detect
      * file-descriptor, thread, and fiber leaks); the scheduler settle still runs whenever any category is enabled.
      *
      * Order: the scheduler/fiber probe first (it owns the settle window), then a `System.gc()` plus settle so Cleaner-closed abandoned
      * channels and finished threads drop out before the descriptor and thread diffs (a genuine leak stays referenced and survives the gc, so
      * this trims false positives without hiding real leaks). The fiber probe matches the allowlist against the busy worker's full stack so an
      * OS-independent kyo frame can excuse an expected event loop; the descriptor probe enumerates `/proc/self/fd` and reports the exact
      * leaked targets with no count tolerance.
      */
    def detect(
        baseline: Baseline,
        allowlist: Chunk[String],
        checkFibers: Boolean,
        checkThreads: Boolean,
        checkFileDescriptors: Boolean,
        checkSockets: Boolean,
        idleBudgetNanos: Long,
        settleNanos: Long,
        pollNanos: Long
    ): Maybe[String] =
        val findings           = Chunk.newBuilder[String]
        val effectiveAllowlist = defaultAllowlist ++ allowlist

        // Always settle on scheduler quiescence first: it lets in-flight fibers finish and release their resources before the thread and
        // descriptor diffs run, which trims false positives for every category. Record a fiber finding only when that category is enabled.
        awaitSchedulerIdle(idleBudgetNanos, settleNanos, pollNanos) match
            case IdleResult.Idle => ()
            case IdleResult.Busy(la, frame) =>
                if checkFibers then
                    val stack       = busyWorkerStack().getOrElse(frame.getOrElse(""))
                    val allowlisted = effectiveAllowlist.exists(stack.contains)
                    if !allowlisted then
                        findings += s"fiber leak: scheduler still busy (loadAvg=$la) after settle; running at ${frame.getOrElse("<unknown frame>")}" +
                            s"\n    busy worker stack:\n$stack" +
                            s"\n  all running threads (worker and non-worker) at probe time:${runningThreadsDump()}"
                    end if
        end match

        System.gc()
        LockSupport.parkNanos(settleNanos)

        if checkThreads then
            val threadLeaks = leakedNonDaemonThreads(baseline.threads, effectiveAllowlist)
            if threadLeaks.nonEmpty then
                findings += s"non-daemon thread leak (${threadLeaks.size}): ${threadLeaks.mkString("; ")}"
        end if

        if checkFileDescriptors || checkSockets then
            baseline.fds match
                case Maybe.Present(before) =>
                    // A descriptor may be mid-close at done(): a client connection closes asynchronously while it processes the
                    // server's FIN (EOF -> pump teardown -> channel close). Require a descriptor to remain leaked across a second
                    // settle so an in-flight close is not mistaken for a leak; a genuinely leaked descriptor never closes and so
                    // survives the recheck. (Safe: this can only drop descriptors that closed during the window, never a real leak.)
                    def leaksNow(): Chunk[String] =
                        val raw = openFdTargets().map(fdLeaks(before, _, effectiveAllowlist)).getOrElse(Chunk.empty)
                        fdLeaksForCategories(raw, checkSockets, checkFileDescriptors)
                    val first = leaksNow()
                    if first.nonEmpty then
                        LockSupport.parkNanos(settleNanos)
                        val second     = leaksNow()
                        val persistent = first.filter(second.contains)
                        if persistent.nonEmpty then
                            val described = persistent.map(t => t + describeSocket(t))
                            findings += s"file-descriptor leak (${persistent.size}): ${described.mkString("; ")}"
                    end if
                case Maybe.Absent => () // /proc/self/fd unavailable: descriptor probe is a no-op on this platform.
            end match
        end if

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
                "test (or the code under test) did not release a resource. Disable for a suite with " +
                "`override def config = super.config.leakCheck(false)`, or excuse one expected resource with " +
                "`super.config.leakCheckAllowlist(\"<stack-or-target-substring>\")`."
        )

end LeakCheck
