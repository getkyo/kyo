package kyo.stats.machine

import kyo.*
import kyo.stats.internal.StatsRegistry

class MachineMetricsTest extends kyo.test.Test[Any]:

    // The host-driven leaf reads the shared process-global "machine" scope every other suite's
    // MachineHandles.init also writes, so the leaves run one at a time.
    override def config: kyo.test.RunConfig = super.config.sequential

    import AllowUnsafe.embrace.danger

    /** Drives every cell in `handles` until it registers, so the registered key set under the handles' own
      * scope is the complete taxonomy. A `RateCell` and a `CounterCell` baseline on their first observation
      * and record on the second, so every cumulative cell is written twice with a rising value.
      */
    private def registerEveryCell(handles: MachineHandles): Unit =
        def rate(cell: MachineHandles.RateCell): Unit =
            cell.observe(1000L)
            cell.observe(2000L)
        rate(handles.cpuTotal)
        rate(handles.cpuUser)
        rate(handles.cpuSystem)
        rate(handles.cpuIdle)
        rate(handles.cpuIowait)
        rate(handles.cpuSteal)
        handles.memTotal.set(1L)
        handles.memAvailable.observe(1L)
        handles.memFree.observe(1L)
        handles.swapTotal.set(1L)
        handles.swapFree.observe(1L)
        handles.loadOne.set(1.0)
        handles.loadFive.set(1.0)
        handles.loadFifteen.set(1.0)
        handles.cgMemUsage.observe(1L)
        handles.cgMemLimit.set(1L)
        handles.cgCpuQuota.set(1L)
        handles.cgCpuPeriod.set(1L)
        handles.cgCpuPeriods.observe(1L)
        handles.cgCpuPeriods.observe(2L)
        rate(handles.cgThrPeriods)
        rate(handles.cgThrTime)
        def psi(pairs: PsiHandles): Unit =
            List(pairs.cpuSome, pairs.memorySome, pairs.memoryFull, pairs.ioSome, pairs.ioFull).foreach { pair =>
                pair.avg10.set(1.0)
                pair.avg60.set(1.0)
                pair.avg300.set(1.0)
                rate(pair.rate)
            }
        psi(handles.systemPressure)
        psi(handles.cgroupPressure)
        val store = handles.diskStore("root")
        store.total.set(1L)
        store.free.observe(1L)
    end registerEveryCell

    /** Every key registered under `root`, read through the public enumeration. */
    private def registeredUnder(root: String): Set[List[String]] =
        StatsRegistry.snapshot(root).map(_.path).toSet

    /** A key rewritten from the "machine" root to a test-private root. */
    private def rerooted(root: String, path: List[String]): List[String] = root :: path.tail

    "the constants are exactly what the sampler registers" in {
        // The guard against the constants drifting from the cells: it enumerates what a fully-driven handle
        // set actually put in the registry and compares it to the taxonomy named in MachineMetrics, with no
        // string reconstruction on either side.
        val root    = "mmetricstest-taxonomy"
        val handles = MachineHandles.initForTest(Stat.initScope(root), 8L)
        registerEveryCell(handles)

        val expectedFixed = MachineMetrics.all.map(k => rerooted(root, k.path))
        val expectedDisk =
            List(MachineMetrics.disk("root").total, MachineMetrics.disk("root").free).map(k => rerooted(root, k.path))
        val expectedPressure =
            MachineMetrics.pressurePairs.flatMap { case (resource, kind) =>
                List(MachineMetrics.pressure(resource, kind), MachineMetrics.cgroupPressure(resource, kind))
                    .flatMap(p => List(p.avg10, p.avg60, p.avg300, p.rate))
                    .map(k => rerooted(root, k.path))
            }

        val expected = (expectedFixed ++ expectedDisk ++ expectedPressure).toSet
        assert(registeredUnder(root) == expected)
    }

    "the fixed taxonomy has no duplicate keys" in {
        assert(MachineMetrics.all.map(_.path).distinct.size == MachineMetrics.all.size)
    }

    "a key carries the split the registry actually uses, which the dotted name does not" in {
        // The two rows that disagree, and the reason this type exists: a `.rate` leaf keeps the dot inside
        // the instrument name, an ordinary leaf does not, and both render identically as dotted strings.
        assert(MachineMetrics.cpuTotalRate.scope == List("machine", "cpu"))
        assert(MachineMetrics.cpuTotalRate.name == "total.rate")
        assert(MachineMetrics.memoryAvailable.scope == List("machine", "memory"))
        assert(MachineMetrics.memoryAvailable.name == "available")
        assert(MachineMetrics.cpuTotalRate.dotted == "machine.cpu.total.rate")
        assert(MachineMetrics.memoryAvailable.dotted == "machine.memory.available")
        assert(MachineMetrics.cpuTotalRate.path == List("machine", "cpu", "total.rate"))
    }

    "reading a key that this host never produced returns Absent and registers nothing" in {
        val absent = MachineMetrics.Key(List("mmetricstest-unregistered", "cpu"), "total.rate", MachineMetrics.Kind.Histogram)
        assert(absent.findHistogram.isEmpty)
        assert(absent.findGauge.isEmpty)
        assert(absent.findCounter.isEmpty)
        assert(registeredUnder("mmetricstest-unregistered").isEmpty)
    }

    "each documented key is the ONLY split of its dotted name that carries data on a real host".onlyJvm in {
        // E3's own brute-force probe, kept as a regression guard: for each documented leaf, try every split
        // point of the dotted name and assert exactly one carries data, the one the constant names. Before
        // the constants existed a consumer had to run this probe to find the keys at all, and a wrong split
        // read back as a permanently flat series rather than an error.
        val hostOs = System.live.unsafe.operatingSystem()
        assume(
            hostOs == System.OS.Linux || hostOs == System.OS.MacOS || hostOs == System.OS.Windows,
            "needs a host OS with a dedicated Machine implementation to register anything"
        )
        for
            handles <- MachineHandles.init
            sampler = new MachineSampler(handles)
            machine = Machine.forOs(hostOs, handles, sampler)
            _       = machine.read()
            _       = machine.readDisks()
            _       = machine.read()
            _       = machine.readDisks()
        yield
            def carriesData(scope: List[String], name: String): Boolean =
                val s = StatsRegistry.scope(scope*)
                s.findHistogram(name).exists(_.summary().count > 0) ||
                s.findGauge(name).isDefined ||
                s.findCounter(name).isDefined
            end carriesData

            val live = MachineMetrics.all.filter(k => carriesData(k.scope, k.name))
            assert(live.nonEmpty)
            live.foreach { k =>
                val segments = k.dotted.split('.').toList
                val splits =
                    (1 until segments.size).map(i => (segments.take(i), segments.drop(i).mkString(".")))
                val carrying = splits.filter((scope, name) => carriesData(scope, name))
                assert(carrying.size == 1, s"${k.dotted} carried data at ${carrying.size} splits")
                assert(carrying.head == (k.scope, k.name))
            }
            succeed(s"${live.size} live keys each carry data at exactly the documented split")
        end for
    }

    "the runtime-named disk family is discoverable without knowing the mount names".onlyJvm in {
        val hostOs = System.live.unsafe.operatingSystem()
        assume(
            hostOs == System.OS.Linux || hostOs == System.OS.MacOS || hostOs == System.OS.Windows,
            "needs a host OS with a dedicated Machine implementation to enumerate mounts"
        )
        for
            handles <- MachineHandles.init
            sampler = new MachineSampler(handles)
            machine = Machine.forOs(hostOs, handles, sampler)
            _       = machine.readDisks()
        yield
            val stores = MachineMetrics.diskStores()
            assert(stores.nonEmpty)
            assert(stores.forall(s => MachineMetrics.disk(s).total.findGauge.isDefined))
        end for
    }

end MachineMetricsTest
