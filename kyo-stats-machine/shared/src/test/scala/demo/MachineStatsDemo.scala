package demo

import kyo.*
import kyo.stats.machine.MachineRegistrySnapshot

/** Host observability with zero setup: put kyo-stats-machine on the classpath and read machine.* metrics.
  *
  * An operator running a kyo service wants CPU, memory, disk, and load telemetry without wiring any
  * monitoring API into their code. This demo is that operator's program: it never calls a Machine method
  * and never starts a sampler by hand. It only touches `kyo.Stat` (the class every metrics-emitting kyo app
  * already touches), which is enough for the classpath-present kyo-stats-machine module to auto-load its
  * exporter factory, start the once-per-second host sampler, and begin feeding the shared `kyo.Stat`
  * registry. After a few sampler ticks the demo reads the registry through `MachineRegistrySnapshot`, which
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
  *   - classpath-presence auto-load: touching `kyo.Stat` alone starts the host sampler, with no user API call
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

    /** The operator flow. Touch `kyo.Stat` to trigger auto-load (no Machine API call), let the sampler run a
      * few ticks, then snapshot the registry the way an exporter reads it.
      */
    def flow(using Frame): Report < (Async & Abort[Throwable]) =
        for
            // The ONLY user action: reference kyo.Stat so object Stat's class-init eager service-loader scan
            // runs, which constructs the classpath-present MachineStatFactory, which starts the sampler.
            // No Machine method is called; this is what a metrics-only app already does.
            _ <- Sync.defer {
                import AllowUnsafe.embrace.danger
                Stat.initScope("app").initCounter("touch", "forces Stat class-init").unsafe.add(0)
            }
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

    /** Assembles the observed readings into the design-derived Report that `validate` then checks. */
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

    /** Design-derived acceptance. Returns Absent when the report proves auto-load fed real host metrics into
      * kyo.Stat, Present(reason) otherwise. Every threshold traces to a physical fact about a running host,
      * not to whatever the sampler happened to produce.
      */
    def validate(r: Report): Maybe[String] =
        if r.sampled.isEmpty then
            Present("no machine.* metrics in the registry: auto-load did not start the sampler")
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
  * Auto-load is triggered by the flow's single `kyo.Stat` touch; the opt-out is read once at that touch, so
  * running under `KYO_MACHINE_DISABLED=true` yields an empty snapshot and a validation failure (the demo's
  * proof that the opt-out suppresses the sampler).
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
