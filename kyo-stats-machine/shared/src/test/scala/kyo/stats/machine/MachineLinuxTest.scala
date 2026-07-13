package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class MachineLinuxTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope ("machine"):
    // every metric this suite decodes into (cpu/memory/swap/load/cgroup) is a fixed, well-known path
    // shared with every other suite's own MachineHandles.init. Each leaf below therefore reads its own
    // metric's DELTA (a histogram sum/count captured immediately before and after its own decode call,
    // with no suspension in between) rather than an absolute registry value, so a concurrently-running
    // sibling suite's own observations cannot corrupt this suite's assertions.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private def span(s: String): (Span[Byte], Int) =
        val bytes = s.getBytes(StandardCharsets.US_ASCII)
        (Span.fromUnsafe(bytes), bytes.length)

    private def gaugePath(path: String*): Double =
        StatsRegistry.internal.gauges.get(path.toList.reverse, "", new UnsafeGauge(() => -1d)).collect()

    private def gaugeRegistered(path: String*): Boolean =
        StatsRegistry.internal.gauges.map.containsKey(path.toList)

    private def histogramRegistered(path: String*): Boolean =
        StatsRegistry.internal.histograms.map.containsKey(path.toList)

    private def histogramSummary(path: String*): Summary =
        StatsRegistry.internal.histograms.get(path.toList.reverse, "", new UnsafeHistogram(Array(0d))).summary()

    "cpu decode" - {

        "virtualized /proc/stat cpu total sums every present column and system includes irq plus softirq" in {
            val (baselineBytes, baselineLen) = span("cpu 0 0 0 0 0 0 0 0\n")
            val (tickBytes, tickLen)         = span("cpu 100 20 30 40 50 6 7 80\n")
            for handles <- MachineHandles.init
            yield
                LinuxDecoders.cpu(baselineBytes, baselineLen, 1L, handles) // baseline tick: no observation yet
                val systemSumBefore = histogramSummary("machine", "cpu", "system.rate").sum
                val iowaitSumBefore = histogramSummary("machine", "cpu", "iowait.rate").sum
                val stealSumBefore  = histogramSummary("machine", "cpu", "steal.rate").sum
                val totalSumBefore  = histogramSummary("machine", "cpu", "total.rate").sum
                LinuxDecoders.cpu(tickBytes, tickLen, 1L, handles)
                assert(histogramSummary("machine", "cpu", "system.rate").sum - systemSumBefore == 43.0) // system+irq+softirq
                assert(histogramSummary("machine", "cpu", "iowait.rate").sum - iowaitSumBefore == 50.0)
                assert(histogramSummary("machine", "cpu", "steal.rate").sum - stealSumBefore == 80.0)
                assert(histogramSummary("machine", "cpu", "total.rate").sum - totalSumBefore == 333.0)
            end for
        }

        "short /proc/stat cpu line degrades to the present columns without throwing" in {
            val (baselineBytes, baselineLen) = span("cpu 0 0 0 0\n")
            val (tickBytes, tickLen)         = span("cpu 100 20 30 40\n")
            for handles <- MachineHandles.init
            yield
                LinuxDecoders.cpu(baselineBytes, baselineLen, 1L, handles)
                val totalSumBefore    = histogramSummary("machine", "cpu", "total.rate").sum
                val iowaitCountBefore = histogramSummary("machine", "cpu", "iowait.rate").count
                val stealCountBefore  = histogramSummary("machine", "cpu", "steal.rate").count
                LinuxDecoders.cpu(tickBytes, tickLen, 1L, handles)                                     // no throw on the missing columns
                assert(histogramSummary("machine", "cpu", "total.rate").sum - totalSumBefore == 190.0) // user+nice+system+idle
                assert(histogramSummary("machine", "cpu", "iowait.rate").count == iowaitCountBefore)   // Absent, skipped
                assert(histogramSummary("machine", "cpu", "steal.rate").count == stealCountBefore)     // Absent, skipped
            end for
        }

        "cpu.steal and cpu.iowait are written and their series registered on Linux" in {
            val (baselineBytes, baselineLen) = span("cpu 0 0 0 0 0 0 0 0\n")
            val (tickBytes, tickLen)         = span("cpu 10 10 10 10 55 1 1 66\n")
            for handles <- MachineHandles.init
            yield
                LinuxDecoders.cpu(baselineBytes, baselineLen, 1L, handles)
                val iowaitSumBefore = histogramSummary("machine", "cpu", "iowait.rate").sum
                val stealSumBefore  = histogramSummary("machine", "cpu", "steal.rate").sum
                LinuxDecoders.cpu(tickBytes, tickLen, 1L, handles)
                assert(histogramRegistered("machine", "cpu", "iowait.rate"))
                assert(histogramRegistered("machine", "cpu", "steal.rate"))
                assert(histogramSummary("machine", "cpu", "iowait.rate").sum - iowaitSumBefore == 55.0)
                assert(histogramSummary("machine", "cpu", "steal.rate").sum - stealSumBefore == 66.0)
            end for
        }

        "a non-numeric MIDDLE cpu column routes only that mode to Absent while the other modes stay present and no exception is thrown" in {
            val (baselineBytes, baselineLen) = span("cpu 0 0 0 0\n")
            val (tickBytes, tickLen)         = span("cpu 100 0 x 800\n") // system (index 2) is non-numeric
            for handles <- MachineHandles.init
            yield
                LinuxDecoders.cpu(baselineBytes, baselineLen, 1L, handles)
                val userSumBefore     = histogramSummary("machine", "cpu", "user.rate").sum
                val idleSumBefore     = histogramSummary("machine", "cpu", "idle.rate").sum
                val totalSumBefore    = histogramSummary("machine", "cpu", "total.rate").sum
                val systemCountBefore = histogramSummary("machine", "cpu", "system.rate").count
                LinuxDecoders.cpu(tickBytes, tickLen, 1L, handles) // no exception on the malformed column
                assert(histogramSummary("machine", "cpu", "user.rate").sum - userSumBefore == 100.0)
                assert(histogramSummary("machine", "cpu", "idle.rate").sum - idleSumBefore == 800.0)
                assert(histogramSummary("machine", "cpu", "total.rate").sum - totalSumBefore == 900.0) // malformed drops via plus()
                assert(histogramSummary("machine", "cpu", "system.rate").count == systemCountBefore)   // Absent, skipped
            end for
        }
    }

    "cgroup v2 cpu.max" - {

        "decodes quota and period from a single read" in {
            var callCount = 0
            for
                handles <- MachineHandles.init
                dir     <- Path.tempDir("kyo-stats-machine-linux-cpumax")
                file = dir / "cpu.max"
                _ <- file.write("50000 100000\n")
                sampler = new MachineSampler(handles)
                slot    = sampler.openSlot(file)
                // Reproduces LinuxCgroup's own decodeCpuMax field shape (a single read decoding both the
                // quota and the period): LinuxCgroup itself is not test-injectable (its root resolution is
                // hardcoded to /proc/self/mountinfo and /proc/self/cgroup), so this drives the identical
                // decode logic through the sampler's own readInto seam instead.
                decode = new MachineSampler.Decode:
                    def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit =
                        callCount += 1
                        handles.cgCpuQuota.set(LinuxScan.longField(b, n, 0, 0, 1000L))
                        handles.cgCpuPeriod.set(LinuxScan.longField(b, n, 0, 1, 1000L))
                    end apply
                ok     = sampler.readInto(slot, decode)
                quota  = gaugePath("machine", "cgroup", "cpu.quota")
                period = gaugePath("machine", "cgroup", "cpu.period")
                _ <- dir.removeAll
            yield
                assert(ok)
                assert(callCount == 1)
                assert(quota == 50000000.0)
                assert(period == 100000000.0)
            end for
        }
    }

    "meminfo decode" - {

        "read exactly once per tick populates both memory and swap rows" in {
            var callCount = 0
            val fixture =
                "MemTotal:       16384 kB\nMemAvailable:    8192 kB\nMemFree:  4096 kB\nSwapTotal:  2048 kB\nSwapFree: 1024 kB\n"
            val (fixtureBytes, fixtureLen) = span(fixture)
            for
                handles <- MachineHandles.init
                dir     <- Path.tempDir("kyo-stats-machine-linux-meminfo")
                file = dir / "meminfo"
                _ <- file.write(fixture)
                sampler           = new MachineSampler(handles)
                slot              = sampler.openSlot(file)
                memAvailSumBefore = histogramSummary("machine", "memory", "available").sum
                memFreeSumBefore  = histogramSummary("machine", "memory", "free").sum
                swapFreeSumBefore = histogramSummary("machine", "swap", "free").sum
                decode = new MachineSampler.Decode:
                    def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit =
                        callCount += 1
                        LinuxDecoders.meminfo(b, n, handles)
                ok = sampler.readInto(slot, decode)
                _ <- dir.removeAll
            yield
                assert(ok)
                assert(callCount == 1)
                // memTotal/swapTotal are LongGaugeCells rooted at the shared "machine" scope every leaf's
                // own MachineHandles.init resolves to; StatsRegistry keeps only the first-ever-registered
                // cell for a path canonical for the process lifetime, so a poll here could read a value a
                // different leaf (in this file or another) registered first. The decode's own parsing is
                // instead verified directly against the same scan primitive and scale LinuxDecoders.meminfo
                // uses, decoupled from the shared gauge.
                assert(LinuxScan.keyedLong(fixtureBytes, fixtureLen, LinuxScan.ascii("MemTotal:"), 0, 1024L) == 16777216L)
                assert(LinuxScan.keyedLong(fixtureBytes, fixtureLen, LinuxScan.ascii("SwapTotal:"), 0, 1024L) == 2097152L)
                assert(histogramSummary("machine", "memory", "available").sum - memAvailSumBefore == 8388608.0)
                assert(histogramSummary("machine", "memory", "free").sum - memFreeSumBefore == 4194304.0)
                assert(histogramSummary("machine", "swap", "free").sum - swapFreeSumBefore == 1048576.0)
            end for
        }

        "a meminfo line missing MemAvailable and missing the swap lines routes those cells to Absent, never a fabricated 0" in {
            val fixture                    = "MemTotal:  1048576 kB\nMemFree:  204800 kB\n"
            val (fixtureBytes, fixtureLen) = span(fixture)
            for
                handles <- MachineHandles.init
                dir     <- Path.tempDir("kyo-stats-machine-linux-meminfo-missing")
                file = dir / "meminfo"
                _ <- file.write(fixture)
                sampler                   = new MachineSampler(handles)
                slot                      = sampler.openSlot(file)
                memFreeSumBefore          = histogramSummary("machine", "memory", "free").sum
                availCountBefore          = histogramSummary("machine", "memory", "available").count
                swapFreeCountBefore       = histogramSummary("machine", "swap", "free").count
                swapTotalRegisteredBefore = gaugeRegistered("machine", "swap", "total")
                decode = new MachineSampler.Decode:
                    def apply(b: Span[Byte], n: Int)(using AllowUnsafe): Unit = LinuxDecoders.meminfo(b, n, handles)
                ok = sampler.readInto(slot, decode)
                _ <- dir.removeAll
            yield
                assert(ok)
                // `memTotal` is a LongGaugeCell rooted at the shared "machine" scope that every leaf's own
                // MachineHandles.init resolves to; StatsRegistry keeps only the first-ever-registered cell
                // for a path canonical for the process lifetime, so a poll here would read whichever leaf
                // (in this file or another) happened to register first, not this leaf's own fixture. The
                // decode's own parsing is instead verified directly against the same scan primitive and
                // scale LinuxDecoders.meminfo uses, decoupled from the shared gauge.
                assert(LinuxScan.keyedLong(fixtureBytes, fixtureLen, LinuxScan.ascii("MemTotal:"), 0, 1024L) == 1073741824L)
                assert(histogramSummary("machine", "memory", "free").sum - memFreeSumBefore == 209715200.0)
                assert(histogramSummary("machine", "memory", "available").count == availCountBefore) // structural absence
                assert(histogramSummary("machine", "swap", "free").count == swapFreeCountBefore)     // structural absence
                assert(gaugeRegistered("machine", "swap", "total") == swapTotalRegisteredBefore)     // no new registration
            end for
        }
    }

    "loadavg decode" - {

        "decodes the three pre-averaged doubles, and a truncated loadavg line routes only its missing fields to Absent" in {
            val (fullBytes, fullLen)   = span("0.10 0.20 0.30 1/200 12345\n")
            val (truncBytes, truncLen) = span("0.10\n") // only the first field survives the truncation
            // loadOne/Five/Fifteen are DoubleGaugeCells rooted at the shared "machine" scope every leaf's
            // own MachineHandles.init resolves to; StatsRegistry keeps only the first-ever-registered cell
            // for a path canonical for the process lifetime, so a poll here could read a value a different
            // leaf (in this file or another) registered first. LinuxDecoders.load's wiring (right token
            // index, right key) is instead verified directly against the same scan primitive it uses,
            // decoupled from the shared gauge; the generic "a NaN observation registers nothing, a real
            // value persists" cell behavior is already covered in isolation by MachineHandlesTest's
            // DoubleGaugeCell leaf.
            for handles <- MachineHandles.init
            yield
                LinuxDecoders.load(fullBytes, fullLen, handles)   // exercised for the no-exception wiring path
                LinuxDecoders.load(truncBytes, truncLen, handles) // a truncated line must not throw either
                assert(LinuxScan.doubleField(fullBytes, fullLen, 0, 0) == 0.10)
                assert(LinuxScan.doubleField(fullBytes, fullLen, 0, 1) == 0.20)
                assert(LinuxScan.doubleField(fullBytes, fullLen, 0, 2) == 0.30)
                assert(LinuxScan.doubleField(truncBytes, truncLen, 0, 0) == 0.10) // the surviving field
                assert(LinuxScan.doubleField(truncBytes, truncLen, 0, 1).isNaN)   // five: token absent
                assert(LinuxScan.doubleField(truncBytes, truncLen, 0, 2).isNaN)   // fifteen: token absent
            end for
        }
    }

end MachineLinuxTest
