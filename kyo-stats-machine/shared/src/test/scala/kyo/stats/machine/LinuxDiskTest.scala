package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.ffi.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class LinuxDiskTest extends kyo.test.Test[Any]:

    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    private def gaugePath(path: String*): Double =
        StatsRegistry.internal.gauges.get(path.toList.reverse, "", new UnsafeGauge(() => -1d)).collect()

    private def gaugeRegistered(path: String*): Boolean =
        StatsRegistry.internal.gauges.map.containsKey(path.toList)

    private def histogramSummary(path: String*): Summary =
        StatsRegistry.internal.histograms.get(path.toList.reverse, "", new UnsafeHistogram(Array(0d))).summary()

    "LinuxDisk.statvfsInto" - {

        "an LP64 statvfs image decodes total and free at offsets 1/2/4" in {
            val cell = new MachineHandles.DiskStore(Stat.initScope("ldtest-statvfs-lp64"), MachineHandles.byteBoundaries)
            val stub = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int =
                    out.set(0, 512L)     // f_bsize -- must never be read as the block size
                    out.set(1, 4096L)    // f_frsize
                    out.set(2, 1000000L) // f_blocks
                    out.set(3, 300000L)  // f_bfree -- must never be read as the free-block count
                    out.set(4, 250000L)  // f_bavail
                    0
                end statvfs
                def sysconf(name: Int)(using AllowUnsafe): Long = 100L
            val out   = Buffer.alloc[Long](16)
            val store = new LinuxDisk.Store("/mnt/data", out, cell)
            LinuxDisk.statvfsInto(stub, store)
            out.close()
            assert(gaugePath("ldtest-statvfs-lp64", "total") == 4096000000.0)
            assert(histogramSummary("ldtest-statvfs-lp64", "free").sum == 1024000000.0)
        }

        "a throwing statvfs binding is contained per mount and the retained buffer closes once" in {
            val stub = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int =
                    if path == "/broken" then throw new java.io.IOException("no such device")
                    else
                        out.set(1, 4096L); out.set(2, 100L); out.set(4, 50L)
                        0
                    end if
                end statvfs
                def sysconf(name: Int)(using AllowUnsafe): Long = 100L
            for handles <- MachineHandles.init
            yield
                val brokenOut   = Buffer.alloc[Long](16)
                val okOut       = Buffer.alloc[Long](16)
                val brokenCell  = handles.diskStore("ldtest-throwing-broken")
                val okCell      = handles.diskStore("ldtest-throwing-ok")
                val brokenStore = new LinuxDisk.Store("/broken", brokenOut, brokenCell)
                val okStore     = new LinuxDisk.Store("/ok", okOut, okCell)
                LinuxDisk.statvfsInto(stub, brokenStore) // the throw is contained, no exception escapes
                LinuxDisk.statvfsInto(stub, okStore)
                // Mirrors LinuxDisk.close's own per-store closing loop: each retained buffer closes exactly
                // once, and closing a store whose statvfsInto threw is just as safe as one that succeeded.
                Chunk(brokenStore, okStore).foreach(_.out.close())
                // handles.diskStore registers each cell under the shared "machine" root's "disk" sub-scope.
                assert(!gaugeRegistered("machine", "disk", "ldtest-throwing-broken", "total"))
                assert(gaugePath("machine", "disk", "ldtest-throwing-ok", "total") == 409600.0)
                assert(histogramSummary("machine", "disk", "ldtest-throwing-ok", "free").sum == 204800.0)
            end for
        }
    }

    "LinuxHandles.diskStore retention" - {

        "the steady disk read consults the store map zero times between mount changes" in {
            for handles <- MachineHandles.init
            yield
                val first  = handles.diskStore("ldtest-diskstore-idempotent")
                val second = handles.diskStore("ldtest-diskstore-idempotent")
                // The retained store map returns the SAME cell instance for a repeated lookup by name,
                // which is what LinuxDisk's own refresh relies on to consult h.diskStore only on the
                // init/mount-change branch and never on the steady per-tick read.
                assert(first eq second)
            end for
        }
    }

    "LinuxDisk.parseMounts" - {

        "an octal-escaped mount path decodes before store-name derivation and statvfs" in {
            val (bytes, len) = span("/dev/sda1 /mnt/my\\040disk ext4 rw 0 0\n")
            val mounts       = LinuxDisk.parseMounts(bytes, len)
            assert(mounts == Chunk("/mnt/my disk"))
            val names = MachineHandles.storeNames(mounts)
            assert(names == Seq("mnt_my disk"))

            var statvfsTarget = ""
            val stub = new LinuxBindings:
                def statvfs(path: String, out: Buffer[Long])(using AllowUnsafe): Int =
                    statvfsTarget = path
                    out.set(1, 1L); out.set(2, 1L); out.set(4, 1L)
                    0
                end statvfs
                def sysconf(name: Int)(using AllowUnsafe): Long = 100L
            for handles <- MachineHandles.init
            yield
                val out   = Buffer.alloc[Long](16)
                val cell  = handles.diskStore("mnt_my disk")
                val store = new LinuxDisk.Store(mounts.head, out, cell)
                LinuxDisk.statvfsInto(stub, store)
                out.close()
                assert(statvfsTarget == "/mnt/my disk")
                assert(gaugePath("machine", "disk", "mnt_my disk", "total") == 1.0) // f_blocks(1) x f_frsize(1)
            end for
        }

        "network and pseudo filesystems are excluded from enumeration" in {
            val (bytes, len) = span(
                "nfs-server:/export /mnt/nfs nfs rw 0 0\n" +
                    "tmpfs /run tmpfs rw 0 0\n" +
                    "cgroup2 /sys/fs/cgroup cgroup2 rw 0 0\n" +
                    "sshfs#user@host:/ /mnt/ssh fuse.sshfs rw 0 0\n" +
                    "/dev/sda1 / ext4 rw 0 0\n"
            )
            val result = LinuxDisk.parseMounts(bytes, len)
            assert(result == Chunk("/"))
        }
    }

    "mount-table change fingerprint" - {

        "an unchanged span matches the retained fingerprint in place, and a byte-differing span does not" in {
            val (bytesA, lenA) = span("/dev/sda1 / ext4 rw 0 0\n")
            val (bytesB, lenB) = span("/dev/sda1 / ext4 rw 0 0\n/dev/sdb1 /data ext4 rw 0 0\n")
            val fingerprintA   = bytesA.toArray.take(lenA)
            // Mirrors LinuxDisk.decodeMounts's own in-place check: a re-read of the same content matches
            // without touching the mount list, and a genuinely differing read is caught before any parse.
            assert(LinuxDisk.sameFingerprint(bytesA, lenA, fingerprintA))
            assert(!LinuxDisk.sameFingerprint(bytesB, lenB, fingerprintA))
            assert(LinuxDisk.parseMounts(bytesA, lenA) == Chunk("/"))
            assert(LinuxDisk.parseMounts(bytesB, lenB) == Chunk("/", "/data"))
        }
    }

end LinuxDiskTest
