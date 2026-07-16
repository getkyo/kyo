package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*

class LinuxCgroupDecodeTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    "LinuxCgroup.limit" - {

        "the v1 unlimited-memory marker routes to AbsentLong through the production LinuxCgroup.limit" in {
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                cgroup  = new LinuxCgroup(handles, sampler)
            yield
                assert(cgroup.limit(1L << 62) == Path.ReadHandle.AbsentLong)
                assert(cgroup.limit((1L << 62) + 1L) == Path.ReadHandle.AbsentLong)
                assert(cgroup.limit(Path.ReadHandle.AbsentLong) == Path.ReadHandle.AbsentLong)
                assert(cgroup.limit((1L << 62) - 1L) == (1L << 62) - 1L)
            end for
        }
    }

    "process-cgroup path resolution" - {

        "v2 cgroup root resolves from a non-default mountinfo cgroup2 mount point" in {
            val (mountinfoBytes, mountinfoLen) = span("24 1 0:21 / /host/sys/fs/cgroup rw,nosuid - cgroup2 cgroup2 rw\n")
            val (cgroupBytes, cgroupLen)       = span("0::/some/cg\n")
            val root                           = LinuxCgroupPath.mountRootV2(mountinfoBytes, mountinfoLen)
            assert(root == Present("/host/sys/fs/cgroup"))
            val dir = LinuxCgroupPath.v2Dir(cgroupBytes, cgroupLen, root.getOrElse("/sys/fs/cgroup"))
            assert(dir == "/host/sys/fs/cgroup/some/cg")
            assert(root != Present("/sys/fs/cgroup"))
        }

        "mountinfo with no cgroup mount falls back to the conventional /sys/fs/cgroup root" in {
            val (bytes, len) = span(
                "24 1 0:21 / / rw - ext4 /dev/sda1 rw\n" +
                    "25 1 0:22 / /tmp rw - tmpfs tmpfs rw\n"
            )
            assert(LinuxCgroupPath.mountRootV2(bytes, len) == Absent)
        }

        "a v1 compound cpu,cpuacct mount maps each controller under its individual key" in {
            val (bytes, len) = span(
                "26 1 0:23 / /sys/fs/cgroup/cpu,cpuacct rw,nosuid,nodev,noexec,relatime shared:11 - cgroup cgroup rw,cpu,cpuacct\n"
            )
            val roots = LinuxCgroupPath.v1MountRoots(bytes, len)
            assert(roots.get("cpu") == Some("/sys/fs/cgroup/cpu,cpuacct"))
            assert(roots.get("cpuacct") == Some("/sys/fs/cgroup/cpu,cpuacct"))
            assert(!roots.contains("cpu,cpuacct"))
        }

        "a v1 controller mounted at a path not matching its name still resolves via mountinfo reconciliation" in {
            val (mountinfoBytes, mountinfoLen) =
                span("30 1 0:24 / /sys/fs/cgroup/mem-alias rw,nosuid shared:12 - cgroup cgroup rw,memory\n")
            val (cgroupBytes, cgroupLen) = span("N:memory:/svc\n")
            val mountRoots               = LinuxCgroupPath.v1MountRoots(mountinfoBytes, mountinfoLen)
            val rels                     = LinuxCgroupPath.v1Rel(cgroupBytes, cgroupLen)
            val reconciled               = LinuxCgroupPath.reconcileV1(mountRoots, rels, "/sys/fs/cgroup")
            assert(reconciled.get("memory") == Some("/sys/fs/cgroup/mem-alias/svc"))
            assert(reconciled.get("memory") != Some("/sys/fs/cgroup/memory/svc"))
        }

        "reconcileV1 falls back to the conventional per-controller layout for a controller absent from mountinfo" in {
            val reconciled = LinuxCgroupPath.reconcileV1(Map.empty, Map("cpu" -> "/svc"), "/sys/fs/cgroup")
            assert(reconciled.get("cpu") == Some("/sys/fs/cgroup/cpu/svc"))

            val (bytes, len) = span("1:cpu,cpuacct:/svc\n")
            val dirs         = LinuxCgroupPath.v1Dirs(bytes, len, "/sys/fs/cgroup")
            assert(dirs.get("cpu") == Some("/sys/fs/cgroup/cpu,cpuacct/svc"))
            assert(dirs.get("cpuacct") == Some("/sys/fs/cgroup/cpu,cpuacct/svc"))
        }

        "a malformed mountinfo line with no separator is skipped without throwing" in {
            val (bytes, len) = span(
                "24 1 0:21 / /truncated-line-no-dash\n" +
                    "25 1 0:22 / /too-short - x\n" +
                    "26 1 0:23 / /sys/fs/cgroup rw - cgroup2 cgroup2 rw\n"
            )
            val root  = LinuxCgroupPath.mountRootV2(bytes, len)
            val roots = LinuxCgroupPath.v1MountRoots(bytes, len)
            assert(root == Present("/sys/fs/cgroup"))
            assert(roots.isEmpty)
        }
    }

    "cgroup v2 vs v1 unit scale" - {

        "cgroup v2 microsecond fields scale x1000 to nanoseconds while v1 nanosecond fields stay x1" in {
            val (v2Bytes, v2Len) = span("nr_periods 10\nnr_throttled 2\nthrottled_usec 5000\n")
            val (v1Bytes, v1Len) = span("nr_periods 10\nnr_throttled 2\nthrottled_time 5000000\n")
            val v2Scaled         = LinuxScan.keyedLong(v2Bytes, v2Len, LinuxCgroup.ThrottledUsec, 0, 1000L)
            val v1Scaled         = LinuxScan.keyedLong(v1Bytes, v1Len, LinuxCgroup.ThrottledTime, 0, 1L)
            assert(v2Scaled == 5000000L)
            assert(v1Scaled == 5000000L)
            assert(v2Scaled == v1Scaled)
        }
    }

    "real-host resolution" - {

        "the mountinfo-based cgroup root resolves to a real, readable cgroup directory for this process on a real Linux host".onlyJvm in {
            assume(
                System.live.unsafe.operatingSystem() == System.OS.Linux,
                "LinuxCgroup's mountinfo-based resolution is Linux-only; this leaf holds on a real Linux host"
            )
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
                cgroup  = new LinuxCgroup(handles, sampler)
                _       = cgroup.read()
                after   = MachineRegistrySnapshot.read
            yield
                def value(path: String): Double = after.find(_.path == path).map(_.value).getOrElse(0.0)
                assert(
                    value("machine.cgroup.cpu.period") > 0.0,
                    "expected machine.cgroup.cpu.period to register with a plausible positive value; a silently mis-resolved cgroup directory would read no such file and leave this metric unregistered"
                )
                assert(
                    value("machine.cgroup.memory.usage") > 0.0,
                    "expected machine.cgroup.memory.usage to register with a plausible positive value for the same reason"
                )
            end for
        }
    }

end LinuxCgroupDecodeTest
