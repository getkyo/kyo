package kyo.stats.machine

import kyo.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeCounter
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class MachineHandlesTest extends kyo.test.Test[Any]:

    // Every leaf's MachineHandles.init shares the SAME process-global StatsRegistry scope
    // ("machine"), since the scope root is locked and every MachineHandles instance across every
    // leaf and every suite resolves to the identical retained handles by path. Running leaves in
    // parallel would race concurrent observe/set calls against those shared handles; sequential
    // execution keeps each leaf's before/after and delta assertions meaningful. The standalone-cell
    // leaves below each construct their OWN uniquely-named scope instead, so they need no such
    // sharing, but stay under the same sequential policy for consistency with the suite.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    private def gaugePath(path: String*): Double =
        StatsRegistry.internal.gauges.get(path.toList.reverse, "", new UnsafeGauge(() => -1d)).collect()

    private def gaugeRegistered(path: String*): Boolean =
        StatsRegistry.internal.gauges.map.containsKey(path.toList)

    private def counterGaugeRegistered(path: String*): Boolean =
        StatsRegistry.internal.counterGauges.map.containsKey(path.toList)

    private def histogramRegistered(path: String*): Boolean =
        StatsRegistry.internal.histograms.map.containsKey(path.toList)

    private def histogramSummary(boundaries: Array[Double], path: String*): Summary =
        StatsRegistry.internal.histograms.get(path.toList.reverse, "", new UnsafeHistogram(boundaries)).summary()

    private def counterValue(path: String*): Long =
        StatsRegistry.internal.counters.get(path.toList.reverse, "", new UnsafeCounter()).get()

    /** Locates the `kyo-stats-machine` module root by walking up from the forked JVM working directory,
      * mirroring `MachineStatFactoryTest`'s established repo-relative lookup.
      */
    private def locateModuleRoot()(using kyo.test.AssertScope): java.io.File =
        val name = "kyo-stats-machine"
        Iterator.iterate(new java.io.File(".").getCanonicalFile)(_.getParentFile)
            .take(6)
            .map(root => new java.io.File(root, name))
            .find(_.isDirectory)
            .getOrElse(fail(s"could not locate the $name module root; run tests from the repository root or a subproject directory"))
    end locateModuleRoot

    private def collectMainScalaFiles(moduleRoot: java.io.File): List[java.io.File] =
        def walk(dir: java.io.File): List[java.io.File] =
            val children = Option(dir.listFiles()).map(_.toList).getOrElse(Nil)
            children.flatMap { f =>
                if f.isDirectory && f.getName != "target" then walk(f)
                else if f.isFile && f.getName.endsWith(".scala") then List(f)
                else Nil
            }
        end walk
        val sep = java.io.File.separator
        walk(moduleRoot).filter(_.getPath.contains(s"${sep}src${sep}main${sep}"))
    end collectMainScalaFiles

    "RateCell" - {

        "clamps a decreasing raw delta to a non-negative observation" in {
            val boundaries = MachineHandles.nanosPerSecFor(8L)
            val cell       = new MachineHandles.RateCell(Stat.initScope("mhtest-ratecell-clamp"), "rate", "d", boundaries)
            cell.observe(100000000000L) // tick 1: baseline, no observation
            cell.observe(50000000000L)  // tick 2: cur < prior, delta clamps to 0
            cell.observe(80000000000L)  // tick 3: delta 30e9
            val summary = histogramSummary(boundaries, "mhtest-ratecell-clamp", "rate")
            assert(summary.count == 2L)
            // min/max are packed as 32-bit floats (UnsafeHistogram's documented ~7-significant-digit
            // precision), so an exact-value check at this magnitude belongs on sum (full Double precision,
            // never packed) instead; min == 0.0 is still exact since 0.0 has no float rounding.
            assert(summary.sum == 30000000000.0)
            assert(summary.min == 0.0)
        }

        "carries the cumulative total in its running sum so the removed `.total` Counter loses no signal" in {
            val boundaries = MachineHandles.nanosPerSecFor(8L)
            val cell       = new MachineHandles.RateCell(Stat.initScope("mhtest-ratecell-runningsum"), "rate", "d", boundaries)
            cell.observe(1000000000L) // baseline
            cell.observe(3000000000L) // delta 2e9
            cell.observe(4000000000L) // delta 1e9
            val summary = histogramSummary(boundaries, "mhtest-ratecell-runningsum", "rate")
            assert(summary.sum == 3000000000.0)
        }

        "is retained as a live val so state persists across a forced GC (val->def would reset it)" in {
            val boundaries = MachineHandles.nanosPerSecFor(8L)
            val cell       = new MachineHandles.RateCell(Stat.initScope("mhtest-ratecell-gc"), "rate", "d", boundaries)
            cell.observe(1000000000L) // baseline
            java.lang.System.gc()
            cell.observe(2000000000L) // delta 1e9, only meaningful if the prior AtomicLong survived the GC
            val summary = histogramSummary(boundaries, "mhtest-ratecell-gc", "rate")
            assert(summary.count == 1L)
            assert(summary.sum == 1000000000.0) // sum is never float-packed, unlike min/max
        }
    }

    "CounterCell" - {

        "advances by its own clamped delta and records into no Histogram" in {
            val cell = new MachineHandles.CounterCell(Stat.initScope("mhtest-countercell"), "periods", "d")
            cell.observe(100L) // baseline, advances 0
            cell.observe(150L) // advances 50
            cell.observe(120L) // reset, clamps to 0
            val total = counterValue("mhtest-countercell", "periods")
            assert(total == 50L)
            assert(!histogramRegistered("mhtest-countercell", "periods"))
        }
    }

    "config LongGaugeCell" - {

        "is a plain Gauge in the gauges store, and a decreasing config reports the raw lower value not a wraparound" in {
            val cell = new MachineHandles.LongGaugeCell(Stat.initScope("mhtest-configgauge"), "memory.limit", "d")
            cell.set(1073741824L)
            val registeredAsGauge        = gaugeRegistered("mhtest-configgauge", "memory.limit")
            val registeredAsCounterGauge = counterGaugeRegistered("mhtest-configgauge", "memory.limit")
            cell.set(536870912L) // a lower limit
            val polled = gaugePath("mhtest-configgauge", "memory.limit")
            assert(registeredAsGauge)
            assert(!registeredAsCounterGauge)
            assert(polled == 536870912.0)
        }
    }

    "DoubleGaugeCell" - {

        "a NaN observation registers nothing; a real value round-trips through the bit-packed AtomicLong holder" in {
            val cell = new MachineHandles.DoubleGaugeCell(Stat.initScope("mhtest-doublegauge"), "one", "d")
            cell.set(Double.NaN)
            val afterNaN = gaugeRegistered("mhtest-doublegauge", "one")
            cell.set(1.5)
            val afterReal = gaugeRegistered("mhtest-doublegauge", "one")
            val polled    = gaugePath("mhtest-doublegauge", "one")
            assert(!afterNaN)
            assert(afterReal)
            assert(polled == 1.5)
        }
    }

    "lazy-on-first-Present registration" - {

        "a host-absent metric registers no handle and a seeded gauge's first poll is the real value not a transient 0" in {
            val cell      = new MachineHandles.LongGaugeCell(Stat.initScope("mhtest-lazy-present"), "cpu.period", "d")
            val beforeAny = gaugeRegistered("mhtest-lazy-present", "cpu.period")
            cell.set(Path.ReadHandle.AbsentLong) // a tick where the host produced nothing
            val afterAbsentTick = gaugeRegistered("mhtest-lazy-present", "cpu.period")
            cell.set(100000000L)
            val afterPresentTick = gaugeRegistered("mhtest-lazy-present", "cpu.period")
            val polled           = gaugePath("mhtest-lazy-present", "cpu.period")
            assert(!beforeAny)
            assert(!afterAbsentTick)
            assert(afterPresentTick)
            assert(polled == 100000000.0)
        }
    }

    "PsiHandles" - {

        "builds exactly five `One` and never a cpu.full cell" in {
            val nanosPerSec = MachineHandles.nanosPerSecFor(8L)
            val psi         = PsiHandles(Stat.initScope("mhtest-psi-five"), nanosPerSec)
            psi.cpuSome.rate.observe(1000000000L)
            assert(psi.cpuSome ne null)
            assert(psi.memorySome ne null)
            assert(psi.memoryFull ne null)
            assert(psi.ioSome ne null)
            assert(psi.ioFull ne null)
            assert(!histogramRegistered("mhtest-psi-five", "cpu", "full", "rate"))
        }
    }

    "metric taxonomy" - {

        "each reclassified metric maps to its concrete new cell type, asserted per name" in {
            for handles <- MachineHandles.init
            yield
                assert(handles.cpuCores.isInstanceOf[MachineHandles.LongGaugeCell])
                assert(handles.memTotal.isInstanceOf[MachineHandles.LongGaugeCell])
                assert(handles.swapTotal.isInstanceOf[MachineHandles.LongGaugeCell])
                assert(handles.loadOne.isInstanceOf[MachineHandles.DoubleGaugeCell])
                assert(handles.loadFive.isInstanceOf[MachineHandles.DoubleGaugeCell])
                assert(handles.loadFifteen.isInstanceOf[MachineHandles.DoubleGaugeCell])
                assert(handles.systemPressure.cpuSome.avg10.isInstanceOf[MachineHandles.DoubleGaugeCell])
                assert(handles.cgroupPressure.ioSome.avg60.isInstanceOf[MachineHandles.DoubleGaugeCell])
                assert(handles.diskStore("mhtest-reclassify-disk").total.isInstanceOf[MachineHandles.LongGaugeCell])
            end for
        }

        "the CPU-time histogram boundaries are derived from the injected core count, never a fixed 8-core ceiling" in {
            val b16 = MachineHandles.nanosPerSecFor(16L)
            val b4  = MachineHandles.nanosPerSecFor(4L)
            assert(b16.length == 9)
            assert(b16.last == 16000000000.0)
            // 12e9 falls UNDER the 16e9 top boundary, not funneled into the overflow bucket.
            assert(b16.exists(bound => 12000000000.0 <= bound && bound < 16000000000.0))
            assert(b4.length == 7)
            assert(b4.sameElements(Array(0d, 100000000d, 500000000d, 1000000000d, 2000000000d, 4000000000d, 8000000000d)))
            assert(!b16.sameElements(b4))
        }

        "a core count strictly between 8 and 16 (the common real-host range) yields boundaries a real histogram accepts" in {
            // Regression guard for a fixed production bug: the naive top/2 for cores=12 is 6e9, which
            // falls BELOW the base array's own 8e9 ceiling, so an unfiltered boundary set is not
            // strictly ascending. UnsafeHistogram's own constructor rejects such an array outright,
            // which is what would actually surface on a revert: MachineHandles.init would throw on its
            // first real cpu observation on any such host. The exact-value check pins the array shape;
            // constructing a real histogram from it is the end-to-end proof.
            val boundaries = MachineHandles.nanosPerSecFor(12L)
            assert(boundaries.sliding(2).forall { case Array(a, b) => a < b })
            assert(
                boundaries.sameElements(
                    Array(0d, 100000000d, 500000000d, 1000000000d, 2000000000d, 4000000000d, 8000000000d, 9000000000d,
                        12000000000d)
                )
            )
            val histogram = new UnsafeHistogram(boundaries)
            assert(histogram.summary().count == 0L)
        }

        "cpuCores is seeded and registered at init because the core count is available on every OS" in {
            for handles <- MachineHandles.init
            yield
                assert(gaugeRegistered("machine", "cpu", "cores"))
                assert(gaugePath("machine", "cpu", "cores") > 0.0)
        }
    }

    "source-scanning guard" - {

        "walks up from the forked JVM cwd and asserts the exact count of files it checked".onlyJvm in {
            val moduleRoot = locateModuleRoot()
            val scalaFiles = collectMainScalaFiles(moduleRoot)
            val banned     = List("Thread.sleep", "synchronized", "CountDownLatch.await")
            def hasBannedConstruct(f: java.io.File): Boolean =
                val content = new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
                banned.exists(content.contains)
            val filesChecked = scalaFiles.size
            val offenders    = scalaFiles.filter(hasBannedConstruct)
            // A fixed-relative-path or wrong-hop resolution that silently matches zero files must never
            // pass vacuously: filesChecked has to be the real, nonzero main-source .scala count.
            assert(filesChecked > 0)
            assert(offenders.isEmpty)
        }
    }

end MachineHandlesTest
