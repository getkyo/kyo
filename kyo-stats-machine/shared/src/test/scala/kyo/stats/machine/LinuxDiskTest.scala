package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.ffi.*

class LinuxDiskTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    given CanEqual[Machine.DiskReading, Machine.DiskReading] = CanEqual.derived

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

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
                result = sampler.readScoped(Path("/does/not/exist/kyo-stats-machine-test"), (b, n) => LinuxDisk.parseMounts(b, n))
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

end LinuxDiskTest
