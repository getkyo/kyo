package kyo.stats.machine

import demo.MachineStatsDemo
import kyo.*

/** Validates the `demo.MachineStatsDemo` acceptance against REAL host metrics inside CI.
  *
  * The runnable demo (`demo.MachineStatsDemoApp`) proves the full classpath auto-load path end to end with a
  * live clock: it touches `kyo.Stat`, the class-init scan constructs the factory, the factory starts the
  * once-per-second sampler, and after a few real seconds `machine.*` metrics appear (see the campaign's
  * run-log evidence). This CI leaf pins the SAME real-host read and the SAME design-derived acceptance
  * deterministically, without depending on a wall-clock detached fiber and without racing sibling suites on
  * the process-global registry: it binds the real per-OS `Machine` impl the sampler resolves and drives the
  * sampler's exact per-tick body (`machineRead` + `diskRead` + `observe`) against the real host. The host
  * FFI/`/proc` read is NOT mocked; only the tick cadence is driven directly so the assertion is deterministic.
  *
  * Where each half is asserted, given a PROCESS-GLOBAL `kyo.Stat` "machine" scope that sibling suites mutate
  * concurrently (parallelism runs suites in parallel; `.sequential` only orders THIS suite's leaves):
  *   - the DESIGN-DERIVED acceptance (families present with plausible host magnitudes; cgroup/PSI Absent on a
  *     non-Linux host; cpu-time advancing) is asserted on the real `Machine.Reading` the host impl returned,
  *     the authoritative and unshared source of "what this host exposes". Reading raw host values off the
  *     reading is immune to a sibling suite draining or polluting the shared registry handles.
  *   - the METRIC-FLOW-INTO-kyo.Stat fact (the sampler's observe reached the registry) is asserted as the
  *     `MachineRegistrySnapshot` snapshot being non-empty, the exporter-facing read the demo uses.
  * The demo's own `validate` hook is then run on a report built from those authoritative reading values, so
  * the demo's published acceptance contract is pinned in CI too.
  *
  * The auto-load PATH is pinned separately: `MachineStatFactoryTest` proves the eager scan reaches the
  * factory, and kyo-core's `StatTest` proves `object Stat` forces the scan at class-init.
  */
class MachineStatsDemoTest extends kyo.test.Test[Any]:

    // Orders THIS suite's single leaf; the process-global "machine" scope is still shared with sibling
    // suites, which is why the design-derived assertions read the authoritative Machine.Reading, not the
    // raced registry handles.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    // A real host reports more than 256 MiB of total RAM and mounts at least one physical filesystem; these
    // thresholds are physical facts about a running host, design-derived, never a value the code computed.
    private val minPlausibleRamBytes = 268435456L

    "the demo's design-derived acceptance holds against a real-host sampler read (auto-load acceptance)".onlyJvm in {
        for
            handles <- MachineHandles.init
            os      <- System.operatingSystem
            sampler = new MachineSampler(handles, Machine.forOs(os))
            // Two authoritative real-host readings a moment apart. cpu-time is cumulative, so the second
            // reading's cpu.total must be >= the first: proof the sampler reads advancing real cpu-time.
            r1    <- Sync.defer(sampler.machineRead())
            disks <- Sync.defer(sampler.diskRead())
            // Observe both readings into the registry so the snapshot is non-empty (metric flow into kyo.Stat).
            _        <- Sync.defer(sampler.observe(r1.copy(disks = disks)))
            r2       <- Sync.defer(sampler.machineRead())
            _        <- Sync.defer(sampler.observe(r2.copy(disks = disks)))
            snapshot <- Sync.defer(MachineRegistrySnapshot.read)
            hostOs   <- Sync.defer(MachineRegistrySnapshot.hostOs)
        yield
            // --- Metric-flow half: the sampler's observe reached the shared kyo.Stat registry. ---
            assert(snapshot.nonEmpty, "no machine.* metrics reached the kyo.Stat registry after observe")

            // --- Design-derived acceptance on the authoritative real reading (race-immune). ---
            val cpu    = r1.cpu.getOrElse(fail("cpu Absent on macOS: the host impl read no cpu-time"))
            val memory = r1.memory.getOrElse(fail("memory Absent on macOS: the host impl read no memory"))
            val load   = r1.load.getOrElse(fail("load Absent on macOS: the host impl read no load average"))

            assert(cpu.total.exists(_ > 0L), s"cpu.total not a positive cumulative ns: ${cpu.total}")
            assert(
                memory.total.exists(_ >= minPlausibleRamBytes),
                s"memory.total implausibly small (${memory.total}); expected > 256 MiB of real RAM"
            )
            assert(disks.nonEmpty, "no physical disk mount observed on the real host")
            assert(load.one.isDefined, s"load.one Absent on macOS: ${load.one}")

            // cgroup / PSI are Linux-only: graceful degradation must leave them Absent on macOS, never faked.
            assert(r1.cgroup.isEmpty, s"cgroup read on a non-Linux host: ${r1.cgroup}")
            assert(r1.pressure.isEmpty, s"system PSI read on a non-Linux host: ${r1.pressure}")
            assert(r1.cgroupPressure.isEmpty, s"cgroup PSI read on a non-Linux host: ${r1.cgroupPressure}")

            // cpu-time is cumulative: the later reading never regresses (proof of advancing real cpu-time).
            val laterCpu = r2.cpu.flatMap(_.total)
            assert(
                laterCpu.exists(l => cpu.total.exists(l >= _)),
                s"cumulative cpu.total regressed between reads: ${cpu.total} -> $laterCpu"
            )

            // --- The demo's published validate hook, pinned on the authoritative reading. ---
            val hostReport = MachineStatsDemo.Report(
                os = hostOs,
                sampled = snapshot,
                cgroupPresent = r1.cgroup.isDefined,
                pressurePresent = r1.pressure.isDefined || r1.cgroupPressure.isDefined,
                memoryTotalBytes = memory.total.map(_.toDouble),
                diskMounts = disks.map(_.store),
                loadOne = load.one,
                cpuTotalNs = cpu.total
            )
            assert(
                MachineStatsDemo.validate(hostReport) == Absent,
                s"the demo's validate hook rejected the real host reading: ${MachineStatsDemo.validate(hostReport)}"
            )
        end for
    }

end MachineStatsDemoTest
