package demo

import kyo.*
import kyo.stats.machine.MachineMetrics
import kyo.stats.machine.MachineRegistrySnapshot

/** Host observability with zero setup: put kyo-stats-machine on the classpath and read machine.* metrics.
  *
  * An operator running a kyo service wants CPU, memory, disk, and load telemetry without wiring any
  * monitoring API into their code. This demo is that operator's program: it never calls a Machine method,
  * never starts a sampler by hand, and never names a metric. Being a `kyo.KyoApp` is the whole of it: the
  * entrypoint reaches `kyo.Stat` before the app's own code runs, which is enough for the classpath-present
  * kyo-stats-machine module to auto-load its exporter factory, start the host sampler, and begin feeding the
  * shared `kyo.Stat` registry. After a few sampler ticks the demo reads the registry through `MachineRegistrySnapshot`, which
  * enumerates it exactly as `OTLPMetricsExporter` does, and reports the machine.* families it found with real
  * values sampled off THIS host.
  *
  * The read is non-destructive on purpose: histograms are read via `summary()` (bucket sums, no reset) and
  * cumulative CPU counters via their retained baseline (`getLast()`), so reading the registry does not drain
  * the values a real exporter would later flush.
  *
  * This is a standalone `main` meant to run on YOUR classpath with kyo-stats-machine present: run it from,
  * or copy it into, an application that depends on the module. It runs on the JVM only, because its
  * `MachineRegistrySnapshot` readback dereferences a `WeakReference`, which does not link under Scala.js/Wasm
  * and throws under Scala Native; the module itself is cross-platform (the test suites cover js, wasm, and
  * native). It is not runnable through this repository's own build, whose test configuration sets the
  * `KYO_MACHINE_DISABLED` opt-out so the module's suites never race a live sampler; under that lever the
  * sampler stays off, the snapshot is empty, and `validate` rejects it. Setting that same env var on your
  * own run is how you watch the opt-out suppress the sampler.
  *
  * Demonstrates:
  *   - classpath-presence auto-load: running as a `KyoApp` starts the host sampler, with no user API call
  *   - the machine.* kyo.Stat metric taxonomy (cpu / memory / swap / disk / load) populated from the real host
  *   - reading the kyo.Stat registry through the exporter-facing store snapshot (MachineRegistrySnapshot)
  *   - graceful degradation: cgroup / PSI families are Absent on a non-Linux host, never faked
  *   - the KYO_MACHINE_DISABLED opt-out suppressing the sampler entirely
  */
object MachineStatsDemo:

    /** What the operator observed after auto-load: the machine.* families present with real values, plus the
      * families that are correctly Absent on this host.
      */
    case class Report(
        os: String,
        sampled: Chunk[MachineRegistrySnapshot.Reading],
        cgroupPresent: Boolean,
        pressurePresent: Boolean,
        memoryTotalBytes: Maybe[Double],
        diskMounts: Chunk[String],
        loadOne: Maybe[Double],
        cpuTotalNs: Maybe[Long]
    ) derives CanEqual

    private val ticksToObserve = 3

    /** The operator flow: let the sampler run a few ticks, then snapshot the registry the way an exporter
      * reads it.
      *
      * There is no user action at all. `kyo.KyoApp`'s entrypoint reaches `kyo.Stat` before running this code,
      * which runs the service-loader scan, which constructs the classpath-present `MachineStatFactory`, which
      * starts the sampler. No Machine method is called and no metric is named; this is what an application
      * that merely has the module on its classpath already does. (A host that is not a `KyoApp` calls
      * `Stat.activate()` once instead; see the module README.)
      */
    def flow(using Frame): Report < (Async & Abort[Throwable]) =
        for
            // Wait past the first sampler tick (which only records the cumulative baseline) plus a couple more,
            // so histograms have observations and CPU counters carry a real cumulative advance.
            _ <- Async.sleep((ticksToObserve + 1).seconds)
            sampled <- Sync.defer {
                import AllowUnsafe.embrace.danger
                MachineRegistrySnapshot.read
            }
            os <- Sync.defer {
                import AllowUnsafe.embrace.danger
                MachineRegistrySnapshot.hostOs
            }
        yield report(os, sampled)
    end flow

    /** Assembles the observed readings into the Report that `validate` checks field by field against real host facts. */
    def report(os: String, sampled: Chunk[MachineRegistrySnapshot.Reading]): Report =
        def valueOf(p: String): Maybe[Double] = Maybe.fromOption(sampled.find(_.path == p).map(_.value))
        val diskMounts =
            sampled.map(_.path).filter(_.startsWith("machine.disk.")).map(_.split('.').lift(2).getOrElse("")).distinct
        Report(
            os = os,
            sampled = sampled,
            cgroupPresent = sampled.exists(_.path.startsWith("machine.cgroup.")),
            pressurePresent = sampled.exists(_.path.startsWith("machine.pressure.")),
            memoryTotalBytes = valueOf("machine.memory.total"),
            diskMounts = diskMounts,
            loadOne = valueOf("machine.load.one"),
            // The cpu.total.rate histogram's running sum carries the cumulative cpu-time total; no
            // separate cumulative Counter exists for it (see MachineHandles' cell-taxonomy scaladoc).
            cpuTotalNs = Maybe.fromOption(sampled.find(_.path == "machine.cpu.total.rate").map(_.sum.toLong))
        )
    end report

    /** The metric families a healthy host must report, named through the module's own key constants rather
      * than string literals.
      *
      * This is the set an audit of the released artifact recorded as present on a working host, and it is what
      * makes the check sensitive to a PARTIAL loss. A host whose native reader is broken outright reports one
      * family (`cpu.cores`, which comes from the JDK's processor count and needs no native), so any check at
      * all catches that; a regression that drops only swap, or only two of the four cpu rates, is invisible
      * unless the whole set is named.
      *
      * Two families are required everywhere except Windows, because that host does not report them and the
      * readers say so rather than faking a value. It has no load-average concept, so the three `load.*`
      * gauges are absent; and `GlobalMemoryStatusEx` exposes no free-versus-available distinction, so
      * `memory.free` is never written rather than repeating the available figure under a second label.
      * Requiring either there would demand a number the module deliberately does not produce. Disk is checked
      * separately, by mount, since its family names are host-specific.
      */
    def requiredKeys(os: String): Chunk[MachineMetrics.Key] =
        val everywhere = Chunk(
            MachineMetrics.cpuTotalRate,
            MachineMetrics.cpuUserRate,
            MachineMetrics.cpuSystemRate,
            MachineMetrics.cpuIdleRate,
            MachineMetrics.cpuCores,
            MachineMetrics.memoryTotal,
            MachineMetrics.memoryAvailable,
            MachineMetrics.swapTotal,
            MachineMetrics.swapFree
        )
        if os == "Windows" then everywhere
        else
            everywhere.concat(Chunk(
                MachineMetrics.memoryFree,
                MachineMetrics.loadOne,
                MachineMetrics.loadFive,
                MachineMetrics.loadFifteen
            ))
        end if
    end requiredKeys

    /** Acceptance check for a Report. Returns Absent when the report proves auto-load fed real host metrics into
      * kyo.Stat, Present(reason) otherwise. Every threshold traces to a physical fact about a running host,
      * not to whatever the sampler happened to produce.
      */
    def validate(r: Report): Maybe[String] =
        val missing = requiredKeys(r.os).filterNot(k => r.sampled.exists(_.path == k.dotted))
        if r.sampled.isEmpty then
            Present("no machine.* metrics in the registry: auto-load did not start the sampler")
        else if missing.nonEmpty then
            Present(s"machine.* families missing on ${r.os}: ${missing.map(_.dotted).mkString(", ")}")
        else if r.memoryTotalBytes.isEmpty then
            Present("machine.memory.total absent: the sampler did not observe host memory")
        // A real host reports more than 256 MiB of total RAM; the memory.total gauge holds that byte count.
        else if r.memoryTotalBytes.exists(_ < 268435456.0) then
            Present(s"machine.memory.total implausibly small (${r.memoryTotalBytes}); expected > 256 MiB of real RAM")
        else if r.diskMounts.isEmpty then
            Present("no machine.disk.<mount> family: the sampler observed no physical mount")
        // Windows has no load-average concept; Linux and macOS both expose one, so only those two hosts
        // require it present.
        else if r.os != "Windows" && r.loadOne.isEmpty then
            Present(s"machine.load.one absent: ${r.os} exposes load average, so it must be present")
        else if r.cpuTotalNs.isEmpty then
            Present("machine.cpu.total.rate absent: the sampler did not accumulate cpu-time")
        else if r.cpuTotalNs.exists(_ <= 0L) then
            Present(s"machine.cpu.total.rate not advancing (${r.cpuTotalNs}); cumulative cpu-time must be > 0 after ticks")
        // cgroup and PSI are Linux-only families: present is the correct outcome there, and their absence
        // must hold everywhere else.
        else if r.os != "Linux" && r.cgroupPresent then
            Present(s"machine.cgroup.* present on a non-Linux host (${r.os}): graceful degradation violated (cgroup must be Absent)")
        else if r.os != "Linux" && r.pressurePresent then
            Present(s"machine.pressure.* present on a non-Linux host (${r.os}): graceful degradation violated (PSI must be Absent)")
        else Absent
        end if
    end validate

    /** Raised by the runnable entry point when `validate` rejects the report, so the process exits non-zero
      * and a failed run surfaces through the exit code instead of only in the printed output.
      */
    final class ValidationFailed(reason: String) extends Exception(reason)

end MachineStatsDemo

/** Runnable entry point. Prints the observed machine.* metrics and the validation verdict; exits 0 when
  * validation passes and non-zero (via [[MachineStatsDemo.ValidationFailed]]) when it does not, so a failed
  * run on your own classpath surfaces through the exit code rather than only in the printed output.
  *
  * Auto-load is triggered by the `KyoApp` entrypoint, with nothing in the flow itself asking for it; the
  * opt-out is read once at that moment, so running under `KYO_MACHINE_DISABLED=true` yields an empty snapshot
  * and a validation failure (the demo's proof that the opt-out suppresses the sampler).
  */
object MachineStatsDemoApp extends KyoApp:
    run {
        for
            report <- MachineStatsDemo.flow
            _      <- Console.printLine(s"host OS: ${report.os}")
            _      <- Console.printLine(s"machine.* metrics observed: ${report.sampled.size}")
            _ <- Kyo.foreachDiscard(report.sampled) { m =>
                Console.printLine(f"  ${m.path}%-40s ${m.kind}%-14s value=${m.value}%,.1f  obs=${m.observations}")
            }
            _ <- Console.printLine(s"cgroup family present: ${report.cgroupPresent} (Linux-only)")
            _ <- Console.printLine(s"PSI family present:    ${report.pressurePresent} (Linux-only)")
            _ <- MachineStatsDemo.validate(report) match
                case Absent       => Console.printLine("\nvalidation: OK (auto-load fed real host metrics into kyo.Stat)")
                case Present(msg) =>
                    // Fail the process, not just the print: a failed validation surfaces through the exit
                    // code, so a run of this main exits non-zero rather than printing and exiting clean.
                    Console.printLineErr(s"\nvalidation FAILED: $msg")
                        .map(_ => Abort.fail(MachineStatsDemo.ValidationFailed(msg)))
        yield ()
    }
end MachineStatsDemoApp
