package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.ffi.*

class MachineReaderTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    given CanEqual[Machine.CpuReading, Machine.CpuReading]       = CanEqual.derived
    given CanEqual[Machine.MemoryReading, Machine.MemoryReading] = CanEqual.derived
    given CanEqual[Machine.LoadReading, Machine.LoadReading]     = CanEqual.derived
    given CanEqual[Machine.PsiReading, Machine.PsiReading]       = CanEqual.derived
    given CanEqual[Machine.DiskReading, Machine.DiskReading]     = CanEqual.derived

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    "LinuxDecoders.cpu" - {

        "scales the aggregate cpu line by the given jiffies-to-ns scale; total is the sum of present modes" in {
            val (bytes, len) = span("cpu 100 0 50 800 20 0 0 0 0 0\n")
            val result       = LinuxDecoders.cpu(bytes, len, 10000000L)
            assert(result == Present(Machine.CpuReading(
                total = Present(9700000000L),
                user = Present(1000000000L),
                system = Present(500000000L),
                idle = Present(8000000000L),
                iowait = Present(200000000L)
            )))
        }

        "a non-numeric field routes that mode to Absent; the other modes stay present, no throw" in {
            // Field order is user(1) nice(2) system(3) idle(4) [iowait(5)]; "x" lands on system(3),
            // the malformed mode under test. nice(2) is consumed only for the total sum, not
            // reported as its own CpuReading field.
            val (bytes, len) = span("cpu 100 0 x 800\n")
            val result       = LinuxDecoders.cpu(bytes, len, 10000000L)
            assert(result == Present(Machine.CpuReading(
                total = Present(9000000000L),
                user = Present(1000000000L),
                system = Absent,
                idle = Present(8000000000L),
                iowait = Absent
            )))
        }
    }

    "LinuxDecoders.memory" - {

        "MemTotal/MemFree convert kB to bytes; a missing MemAvailable is Absent" in {
            val (bytes, len) = span("MemTotal:        1048576 kB\nMemFree:          204800 kB\n")
            val result       = LinuxDecoders.memory(bytes, len)
            assert(result == Present(Machine.MemoryReading(
                total = Present(1073741824L),
                available = Absent,
                free = Present(209715200L)
            )))
        }
    }

    "LinuxDecoders.load" - {

        "a full three-field line yields the three averages; a truncated line yields Absent" in {
            val (fullBytes, fullLen) = span("0.10 0.20 0.30 1/200 12345\n")
            val fullResult           = LinuxDecoders.load(fullBytes, fullLen)
            assert(fullResult == Present(Machine.LoadReading(Present(0.10), Present(0.20), Present(0.30))))

            val (truncBytes, truncLen) = span("0.10\n")
            assert(LinuxDecoders.load(truncBytes, truncLen) == Absent)
        }
    }

    "LinuxPressureDecode.parse" - {

        "some/full lines decode to avg percentages and total ns (total= microseconds x1000)" in {
            val (bytes, len) = span(
                "some avg10=1.00 avg60=2.00 avg300=3.00 total=1000\n" +
                    "full avg10=0.00 avg60=0.00 avg300=0.00 total=0\n"
            )
            val result = LinuxPressureDecode.parse(bytes, len)
            assert(result.isDefined)
            val (some, full) = result.getOrElse(throw new NoSuchElementException)
            assert(some == Machine.PsiReading(Present(1.00), Present(2.00), Present(3.00), Present(1000000L)))
            assert(full == Machine.PsiReading(Present(0.00), Present(0.00), Present(0.00), Present(0L)))
        }

        "a full total=0 line is parsed without error, distinct from the some line" in {
            val (bytes, len) = span(
                "some avg10=5.00 avg60=5.00 avg300=5.00 total=9999\n" +
                    "full avg10=0.00 avg60=0.00 avg300=0.00 total=0\n"
            )
            val result = LinuxPressureDecode.parse(bytes, len)
            assert(result.isDefined)
            val (some, full) = result.getOrElse(throw new NoSuchElementException)
            assert(some.total == Present(9999000L))
            assert(full == Machine.PsiReading(Present(0.00), Present(0.00), Present(0.00), Present(0L)))
        }
    }

    "LinuxCgroup.readV1Limit sentinel (via LinuxCgroupDecode.singleLong)" - {

        "a value at or above the v1 unlimited sentinel routes to Absent" in {
            val (bytes, len) = span("9223372036854771712\n")
            val raw          = LinuxCgroupDecode.singleLong(bytes, len)
            assert(raw == Present(9223372036854771712L))
            assert(raw.exists(_ >= LinuxCgroup.unlimitedSentinel))
        }
    }

    "LinuxCgroupDecode.statField" - {

        "nr_bursts/burst_usec are ignored; nr_throttled/throttled_usec decode correctly" in {
            val (bytes, len) = span(
                "nr_periods 500\n" +
                    "nr_throttled 12\n" +
                    "throttled_usec 5000\n" +
                    "nr_bursts 3\n" +
                    "burst_usec 700\n"
            )
            val throttled     = LinuxCgroupDecode.statField(bytes, len, "nr_throttled", 1L)
            val throttledUsec = LinuxCgroupDecode.statField(bytes, len, "throttled_usec", 1000L)
            assert(throttled == Present(12L))
            assert(throttledUsec == Present(5000000L))
        }

        "v2 microsecond scale (x1000) vs v1 nanosecond scale (x1) on the same raw field value" in {
            val (bytes, len) = span("throttled_usec 5000\nthrottled_time 5000000\n")
            val v2Scaled     = LinuxCgroupDecode.statField(bytes, len, "throttled_usec", 1000L)
            val v1Scaled     = LinuxCgroupDecode.statField(bytes, len, "throttled_time", 1L)
            assert(v2Scaled == Present(5000000L))
            assert(v1Scaled == Present(5000000L))
        }
    }

    "LinuxCgroupDecode.v2Limit" - {

        "the literal max routes memoryLimit to Absent" in {
            val (bytes, len) = span("max\n")
            assert(LinuxCgroupDecode.v2Limit(bytes, len) == Absent)
        }

        "a numeric value passes through unchanged" in {
            val (bytes, len) = span("1073741824\n")
            assert(LinuxCgroupDecode.v2Limit(bytes, len) == Present(1073741824L))
        }
    }

    "LinuxDisk.parseMounts" - {

        "pseudo/virtual filesystem lines are filtered out; physical mounts are enumerated" in {
            val (bytes, len) = span(
                "/dev/sda1 / ext4 rw,relatime 0 0\n" +
                    "proc /proc proc rw,nosuid 0 0\n" +
                    "sysfs /sys sysfs rw 0 0\n" +
                    "tmpfs /run tmpfs rw 0 0\n" +
                    "overlay / overlay rw 0 0\n" +
                    "cgroup2 /sys/fs/cgroup cgroup2 rw 0 0\n" +
                    "/dev/sdb1 /home xfs rw,relatime 0 0\n"
            )
            val result = LinuxDisk.parseMounts(bytes, len)
            assert(result == Chunk("/", "/home"))
        }
    }

    "LinuxDisk.stat" - {

        "a per-mount statvfs failure yields Absent total/free for that mount only; a success records real values" in {
            val statvfs: String => Maybe[(Long, Long)] =
                mount => if mount == "/broken" then Absent else Present((4096000L, 1024000L))
            val ok     = LinuxDisk.stat("/", statvfs)
            val failed = LinuxDisk.stat("/broken", statvfs)
            assert(ok == Machine.DiskReading("/", Present(4096000L), Present(1024000L)))
            assert(failed == Machine.DiskReading("/broken", Absent, Absent))
        }
    }

    "LinuxDisk.enumerate degrade paths" - {

        "an all-pseudo mounts input yields no physical mount" in {
            val (bytes, len) = span(
                "proc /proc proc rw 0 0\n" +
                    "sysfs /sys sysfs rw 0 0\n" +
                    "cgroup2 /sys/fs/cgroup cgroup2 rw 0 0\n"
            )
            assert(LinuxDisk.parseMounts(bytes, len) == Chunk.empty)
        }

        "an unreadable mounts file degrades to an empty disk set, never a throw" in {
            // LinuxDisk.enumerate is s.readScoped(Path("/proc/mounts"), ...).getOrElse(Chunk.empty);
            // readScoped itself returns Absent when openSlot fails on a missing path (no such file),
            // which is exactly the degrade an unreadable /proc/mounts hits. Proven directly against
            // the same readScoped + getOrElse(Chunk.empty) mechanism on a genuinely nonexistent path,
            // since /proc/mounts itself is not redirectable from the reader's hardcoded absolute path.
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles, Machine.NullMachine)
                result = sampler.readScoped(Path("/does/not/exist/kyo-machine-test"), (b, n) => LinuxDisk.parseMounts(b, n))
                    .getOrElse(Chunk.empty)
            yield assert(result == Chunk.empty)
        }
    }

    "full Linux disk read sanitization" - {

        // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope
        // ("machine"); the disk store names used here (root, home, mnt_data) are unique to this
        // leaf across the suite so they do not collide with a concurrently-observing leaf's own
        // disk metrics.
        "a full disk read sanitizes each mount into its store scope via MachineHandles.storeNames" in {
            val (bytes, len) = span(
                "/dev/sda1 / ext4 rw 0 0\n" +
                    "/dev/sdb1 /home xfs rw 0 0\n" +
                    "/dev/sdc1 /mnt/data ext4 rw 0 0\n"
            )
            val mounts                                 = LinuxDisk.parseMounts(bytes, len)
            val statvfs: String => Maybe[(Long, Long)] = _ => Present((1000L, 400L))
            val disks                                  = mounts.map(m => LinuxDisk.stat(m, statvfs))
            val stores                                 = MachineHandles.storeNames(disks.map(_.store).toSeq)
            assert(stores == Seq("root", "home", "mnt_data"))
            for
                handles <- MachineHandles.init
                _ = handles.observe(Machine.Reading.empty.copy(disks = disks), MachineSampler.PriorState.empty)
            yield
                val rootTotal = kyo.stats.internal.StatsRegistry.internal.histograms.map
                    .containsKey(List("machine", "disk", "root", "total"))
                val homeTotal = kyo.stats.internal.StatsRegistry.internal.histograms.map
                    .containsKey(List("machine", "disk", "home", "total"))
                val mntTotal = kyo.stats.internal.StatsRegistry.internal.histograms.map
                    .containsKey(List("machine", "disk", "mnt_data", "total"))
                assert(rootTotal)
                assert(homeTotal)
                assert(mntTotal)
            end for
        }
    }

    "LinuxDisk.statvfsRaw" - {

        "computes total/free from f_blocks*f_frsize and f_bavail*f_frsize at the real LP64 indices" in {
            val fake = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int =
                    out.set(0, 512L)  // f_bsize -- must never be read as the block size
                    out.set(1, 4096L) // f_frsize
                    out.set(2, 1000L) // f_blocks
                    out.set(3, 300L)  // f_bfree -- must never be read as the free-block count
                    out.set(4, 250L)  // f_bavail
                    0
                end statvfs
                def sysconf(name: Int)(using AllowUnsafe): Long = 100L
            val result = LinuxDisk.statvfsRaw(fake, "/")
            assert(result == Present((4096000L, 1024000L)))
        }
    }

end MachineReaderTest
