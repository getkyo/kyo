package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*

class MachineLinuxTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope
    // ("machine"), since the scope root is locked and every MachineHandles instance across every
    // leaf and every suite resolves to the identical retained handles by path. The PSI
    // family-independence leaf below observes into that shared registry, so it runs sequentially
    // with the rest of this suite.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    "cgroup v2 vs v1 unit scale" - {

        "v2 detected: throttled_usec decodes at the microsecond scale (x1000)" in {
            val (bytes, len)  = span("nr_periods 10\nnr_throttled 2\nthrottled_usec 5000\n")
            val throttledTime = LinuxCgroupDecode.statField(bytes, len, "throttled_usec", 1000L)
            assert(throttledTime == Present(5000000L))
        }

        "v1 detected: throttled_time decodes at the nanosecond scale (x1, no microsecond scale)" in {
            val (bytes, len)  = span("nr_periods 10\nnr_throttled 2\nthrottled_time 5000000\n")
            val throttledTime = LinuxCgroupDecode.statField(bytes, len, "throttled_time", 1L)
            assert(throttledTime == Present(5000000L))
        }
    }

    "process-cgroup path resolution" - {

        "v2: the resolved dir joins the mount root with the 0::<path> line, not the hierarchy root" in {
            val (bytes, len) = span("0::/system.slice/foo.service\n")
            val dir          = LinuxCgroupPath.v2Dir(bytes, len, "/sys/fs/cgroup")
            assert(dir == "/sys/fs/cgroup/system.slice/foo.service")
        }

        "v1: each controller in a comma-joined line maps to its own individual key, never the compound name" in {
            val (bytes, len) = span(
                "4:cpu,cpuacct:/system.slice/foo.service\n" +
                    "8:memory:/system.slice/foo.service\n"
            )
            val dirs = LinuxCgroupPath.v1Dirs(bytes, len, "/sys/fs/cgroup")
            assert(dirs.get("cpu") == Some("/sys/fs/cgroup/cpu,cpuacct/system.slice/foo.service"))
            assert(dirs.get("cpuacct") == Some("/sys/fs/cgroup/cpu,cpuacct/system.slice/foo.service"))
            assert(dirs.get("memory") == Some("/sys/fs/cgroup/memory/system.slice/foo.service"))
            assert(dirs.get("cpu,cpuacct") == None)
            // The cpu key resolution mirrors LinuxCgroup.readV1's own lookup precedence: the
            // individual "cpu" key wins over the compound mount-root fallback used when neither
            // individual key is present.
            val resolvedCpu = dirs.get("cpu").orElse(dirs.get("cpuacct")).getOrElse("/sys/fs/cgroup/cpu,cpuacct")
            assert(resolvedCpu == "/sys/fs/cgroup/cpu,cpuacct/system.slice/foo.service")
        }

        "root fallback: a 0::/ root line resolves to the mount root itself" in {
            val (bytes, len) = span("0::/\n")
            val dir          = LinuxCgroupPath.v2Dir(bytes, len, "/sys/fs/cgroup")
            assert(dir == "/sys/fs/cgroup")
        }

        "root fallback: an empty or unparseable cgroup file resolves to the mount root" in {
            val (bytes, len) = span("garbage, no zero-colon-colon line\n")
            val dir          = LinuxCgroupPath.v2Dir(bytes, len, "/sys/fs/cgroup")
            assert(dir == "/sys/fs/cgroup")
        }

        "a non-root process line resolves to the process cgroup dir, distinct from the mount root" in {
            // The production v2Dir resolves the process cgroup dir from the 0::<path> line; a non-root
            // process resolves to a dir UNDER the mount root, which is where LinuxCgroup.readV2 reads every
            // resource file (never the bare mount root, where a non-root process's cpu.stat does not exist).
            // The distinctness from the mount root is the property that makes the cgroup family reachable at
            // all for a non-root process.
            val (bytes, len) = span("0::/system.slice/foo.service\n")
            val resolvedDir  = LinuxCgroupPath.v2Dir(bytes, len, "/sys/fs/cgroup")
            assert(resolvedDir == "/sys/fs/cgroup/system.slice/foo.service")
            assert(resolvedDir != "/sys/fs/cgroup")
        }
    }

    "PSI family independence" - {

        "system PSI and cgroup PSI advance their own total Counters from distinct source deltas, never a shared advance" in {
            def pressureReading(totalNs: Long): Machine.PressureReading =
                Machine.PressureReading(
                    cpuSome = Machine.PsiReading(Absent, Absent, Absent, Present(totalNs)),
                    cpuFull = Machine.PsiReading.empty,
                    memorySome = Machine.PsiReading.empty,
                    memoryFull = Machine.PsiReading.empty,
                    ioSome = Machine.PsiReading.empty,
                    ioFull = Machine.PsiReading.empty
                )
            for
                handles <- MachineHandles.init
                st1 = handles.systemPressure.observe(Present(pressureReading(1000L)), MachineSampler.PriorState.empty, "pressure")
                st2 = handles.cgroupPressure.observe(Present(pressureReading(500L)), st1, "cgroup.pressure")
                st3 = handles.systemPressure.observe(Present(pressureReading(1400L)), st2, "pressure")
                st4 = handles.cgroupPressure.observe(Present(pressureReading(900L)), st3, "cgroup.pressure")
            yield
                // system baseline at tick 1 (advance 0), advances by 400 at tick 3
                // cgroup baseline at tick 1 (advance 0), advances by 400 at tick 3
                assert(st4.get("pressure.cpu.some") == Present(1400L))
                assert(st4.get("cgroup.pressure.cpu.some") == Present(900L))
            end for
        }
    }

    "cgroup v2 PSI absence on a v1 host" - {

        "a v1 host's missing *.pressure content parses to Absent for every resource, so the whole family is Absent" in {
            // LinuxPressure.readCgroup gates on LinuxCgroup.hasV2Pressure (the cgroup.controllers
            // marker, false on a v1 host by construction) before reading any *.pressure file; a v1
            // host never reaches LinuxPressureDecode.parse for the cgroup family at all. What IS
            // exercised here is the degrade LinuxPressure.readFamily relies on when a *.pressure
            // read comes back empty (a v1 host would present zero bytes for a file that does not
            // exist, the same shape a missing file produces via readScoped): an empty span parses
            // to Absent for both some and full, matching the "all three resources Absent" precondition
            // LinuxPressure.readFamily checks before collapsing the whole PressureReading to Absent.
            val (bytes, len) = span("")
            assert(LinuxPressureDecode.parse(bytes, len) == Absent)
        }
    }

end MachineLinuxTest
