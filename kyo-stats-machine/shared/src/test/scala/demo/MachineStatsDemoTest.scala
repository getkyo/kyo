package demo

import kyo.*
import kyo.stats.machine.MachineMetrics
import kyo.stats.machine.MachineRegistrySnapshot

/** Tests for the demo's acceptance check.
  *
  * [[MachineStatsDemo.validate]] is what decides whether auto-load actually fed real host metrics into
  * `kyo.Stat`, and `MachineStatFactoryJvmTest` runs the whole thing in a forked JVM and asserts on its verdict.
  * That fork is an all-or-nothing signal: a native that fails to load reports exactly one family, so any check
  * at all rejects it. What the fork cannot show is the case the check was strengthened FOR, a partial loss,
  * because there is no lever that makes a real host drop only its swap gauges.
  *
  * `validate` is a pure function of a `Report`, so that case is exact here: hand it a snapshot with one family
  * removed and require it to name that family. Without this, "the check now catches a partial regression" is
  * an argument rather than a result.
  */
class MachineStatsDemoTest extends kyo.test.Test[Any]:

    /** A reading per required family, shaped like the ones a real sampler produces. */
    private def readingsFor(keys: Seq[MachineMetrics.Key]): Chunk[MachineRegistrySnapshot.Reading] =
        Chunk.from(keys.map { k =>
            val gauge = k.kind == MachineMetrics.Kind.Gauge
            MachineRegistrySnapshot.Reading(
                path = k.dotted,
                kind = if gauge then "gauge" else "histogram",
                // memory.total is checked against a physical floor, so it carries a plausible byte count.
                value = if k == MachineMetrics.memoryTotal then 17179869184.0 else 1.0,
                observations = if gauge then 1L else 3L,
                sum = if k == MachineMetrics.cpuTotalRate then 1000.0 else 1.0
            )
        })

    /** A host snapshot that passes: every required family, plus the one disk mount `validate` also demands. */
    private def healthyReport(os: String): MachineStatsDemo.Report =
        val disk = MachineMetrics.disk("root")
        val readings = readingsFor(MachineStatsDemo.requiredKeys(os))
            .append(MachineRegistrySnapshot.Reading(disk.total.dotted, "gauge", 1.0, 1L, 1.0))
        MachineStatsDemo.report(os, readings)
    end healthyReport

    "requiredKeys" - {

        "names the thirteen families the audit recorded on a host with a load average" in {
            val keys = MachineStatsDemo.requiredKeys("MacOS").map(_.dotted)
            assert(keys.size == 13)
            assert(keys.contains("machine.cpu.total.rate"))
            assert(keys.contains("machine.cpu.user.rate"))
            assert(keys.contains("machine.cpu.system.rate"))
            assert(keys.contains("machine.cpu.idle.rate"))
            assert(keys.contains("machine.cpu.cores"))
            assert(keys.contains("machine.memory.total"))
            assert(keys.contains("machine.memory.available"))
            assert(keys.contains("machine.memory.free"))
            assert(keys.contains("machine.swap.total"))
            assert(keys.contains("machine.swap.free"))
            assert(keys.contains("machine.load.one"))
            assert(keys.contains("machine.load.five"))
            assert(keys.contains("machine.load.fifteen"))
        }

        "drops the load gauges and memory.free on Windows, the two families that host does not report" in {
            val windows = MachineStatsDemo.requiredKeys("Windows").map(_.dotted)
            val macos   = MachineStatsDemo.requiredKeys("MacOS").map(_.dotted)
            assert(windows.size == 9)
            val dropped = macos.filterNot(windows.contains)
            assert(dropped.size == 4)
            assert(dropped.contains("machine.load.one"))
            assert(dropped.contains("machine.load.five"))
            assert(dropped.contains("machine.load.fifteen"))
            // MachineWindows never writes memory.free: GlobalMemoryStatusEx has no free-versus-available
            // distinction, so requiring it there asks for a value the reader deliberately does not produce.
            assert(dropped.contains("machine.memory.free"))
            assert(windows.forall(macos.contains))
        }

        "every required key is one the module's own taxonomy declares" in {
            // The demo names the set independently of MachineMetrics.all; this pins that it never drifts into
            // naming a family the module does not actually publish.
            val declared = MachineMetrics.all.map(_.dotted)
            val required = MachineStatsDemo.requiredKeys("Linux").map(_.dotted)
            assert(required.forall(declared.contains), s"not in the taxonomy: ${required.filterNot(declared.contains)}")
        }
    }

    "validate" - {

        "accepts a snapshot carrying every required family" in {
            assert(MachineStatsDemo.validate(healthyReport("MacOS")) == Absent)
        }

        "rejects a snapshot that lost exactly one family, and names it" in {
            val full    = healthyReport("MacOS")
            val without = full.sampled.filterNot(_.path == MachineMetrics.swapFree.dotted)
            val report  = MachineStatsDemo.report("MacOS", without)

            // The control, stated as assertions rather than as a claim about an older revision: this report
            // satisfies every condition the check tested BEFORE the required set was named, so its rejection
            // below can only come from the missing family and not from an incidental flaw in the fixture.
            assert(report.sampled.nonEmpty)
            assert(report.memoryTotalBytes.exists(_ > 268435456.0))
            assert(report.diskMounts.nonEmpty)
            assert(report.loadOne.isDefined)
            assert(report.cpuTotalNs.exists(_ > 0L))
            assert(!report.cgroupPresent && !report.pressurePresent)

            val verdict = MachineStatsDemo.validate(report)
            assert(verdict.isDefined, "a snapshot missing machine.swap.free must be rejected")
            assert(verdict.exists(_.contains("machine.swap.free")), s"the verdict must name the family: $verdict")
        }

        "rejects a snapshot that lost two of the four cpu rates" in {
            val full = healthyReport("Linux")
            val without = full.sampled.filterNot(r =>
                r.path == MachineMetrics.cpuUserRate.dotted || r.path == MachineMetrics.cpuIdleRate.dotted
            )
            val verdict = MachineStatsDemo.validate(MachineStatsDemo.report("Linux", without))
            assert(verdict.exists(_.contains("machine.cpu.user.rate")))
            assert(verdict.exists(_.contains("machine.cpu.idle.rate")))
        }

        "rejects an empty snapshot as auto-load never having started the sampler" in {
            val verdict = MachineStatsDemo.validate(MachineStatsDemo.report("MacOS", Chunk.empty))
            assert(verdict.exists(_.contains("auto-load did not start the sampler")))
        }

        "does not require the load gauges or memory.free of a Windows host" in {
            assert(MachineStatsDemo.validate(healthyReport("Windows")) == Absent)
        }

        "accepts a Windows snapshot shaped like the one a real Windows host produces" in {
            // The fixture above draws its readings from requiredKeys itself, so it cannot tell whether that
            // set asks for a family Windows never writes. This one is built from the full taxonomy minus what
            // MachineWindows documents it does not write, which is what the host actually hands over.
            val absentOnWindows = Set(
                MachineMetrics.memoryFree.dotted,
                MachineMetrics.loadOne.dotted,
                MachineMetrics.loadFive.dotted,
                MachineMetrics.loadFifteen.dotted
            )
            val disk = MachineMetrics.disk("C:\\")
            val readings = readingsFor(MachineStatsDemo.requiredKeys("Linux").filterNot(k => absentOnWindows(k.dotted)))
                .append(MachineRegistrySnapshot.Reading(disk.total.dotted, "gauge", 1.0, 1L, 1.0))
            assert(MachineStatsDemo.validate(MachineStatsDemo.report("Windows", readings)) == Absent)
        }
    }

end MachineStatsDemoTest
