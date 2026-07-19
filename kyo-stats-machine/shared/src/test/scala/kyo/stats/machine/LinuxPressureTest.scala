package kyo.stats.machine

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.stats.internal.StatsRegistry
import kyo.stats.internal.Summary
import kyo.stats.internal.UnsafeGauge
import kyo.stats.internal.UnsafeHistogram

class LinuxPressureTest extends kyo.test.Test[Any]:

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

    "LinuxPressure.observeLine" - {

        "a PSI some line decodes avg fields to gauges and total to a scaled rate" in {
            val (baselineBytes, baselineLen) = span("some avg10=0.00 avg60=0.00 avg300=0.00 total=0\n")
            val (tickBytes, tickLen)         = span("some avg10=1.10 avg60=2.20 avg300=3.30 total=4000\n")
            val psi                          = PsiHandles(Stat.initScope("lptest-someline"), MachineHandles.nanosPerSecFor(8L))
            LinuxPressure.observeLine(baselineBytes, baselineLen, LinuxPressure.SomeLine, psi.cpuSome) // baseline tick
            LinuxPressure.observeLine(tickBytes, tickLen, LinuxPressure.SomeLine, psi.cpuSome)
            val rateSummary = histogramSummary("lptest-someline", "cpu", "some", "rate")
            assert(gaugePath("lptest-someline", "cpu", "some", "avg10") == 1.10)
            assert(gaugePath("lptest-someline", "cpu", "some", "avg60") == 2.20)
            assert(gaugePath("lptest-someline", "cpu", "some", "avg300") == 3.30)
            assert(rateSummary.count == 1L)
            assert(rateSummary.sum == 4000000.0) // total=4000us -> x1000 ns
        }

        "the cpu PSI decoder never observes a full row" in {
            val (bytes, len) = span(
                "some avg10=1.00 avg60=1.00 avg300=1.00 total=1000\n" +
                    "full avg10=9.00 avg60=9.00 avg300=9.00 total=9000\n"
            )
            val psi    = PsiHandles(Stat.initScope("lptest-cpu-nofull"), MachineHandles.nanosPerSecFor(8L))
            val decode = new LinuxPressure.PsiDecode(psi.cpuSome, Absent) // cpu carries no full cells, matching production
            decode.apply(bytes, len)
            assert(gaugePath("lptest-cpu-nofull", "cpu", "some", "avg10") == 1.00)
            assert(!histogramRegistered("lptest-cpu-nofull", "cpu", "full", "rate"))
            assert(!gaugeRegistered("lptest-cpu-nofull", "cpu", "full", "avg10"))
        }

        "the memory and io PSI decoders observe both some and full rows" in {
            val (baselineBytes, baselineLen) = span(
                "some avg10=1.00 avg60=1.00 avg300=1.00 total=1000\n" +
                    "full avg10=2.00 avg60=2.00 avg300=2.00 total=2000\n"
            )
            val (tickBytes, tickLen) = span(
                "some avg10=1.50 avg60=1.50 avg300=1.50 total=3000\n" +
                    "full avg10=2.50 avg60=2.50 avg300=2.50 total=5000\n"
            )
            val psi    = PsiHandles(Stat.initScope("lptest-io-full"), MachineHandles.nanosPerSecFor(8L))
            val decode = new LinuxPressure.PsiDecode(psi.ioSome, Present(psi.ioFull))
            decode.apply(baselineBytes, baselineLen) // baseline tick for both rate cells
            decode.apply(tickBytes, tickLen)
            val someRate = histogramSummary("lptest-io-full", "io", "some", "rate")
            val fullRate = histogramSummary("lptest-io-full", "io", "full", "rate")
            assert(someRate.sum == 2000000.0) // (3000-1000)us x1000
            assert(fullRate.sum == 3000000.0) // (5000-2000)us x1000
        }
    }

    "LinuxPressure.read" - {

        "an absent PSI file writes and registers nothing" in {
            for
                handles <- MachineHandles.init
                sampler = new MachineSampler(handles)
            yield
                val psi    = PsiHandles(Stat.initScope("lptest-absent-file"), MachineHandles.nanosPerSecFor(8L))
                val decode = new LinuxPressure.PsiDecode(psi.cpuSome, Absent)
                val ok     = sampler.readInto(Absent, decode) // an Absent slot is exactly what a missing PSI file yields
                assert(!ok)
                assert(!gaugeRegistered("lptest-absent-file", "cpu", "some", "avg10"))
                assert(!histogramRegistered("lptest-absent-file", "cpu", "some", "rate"))
            end for
        }
    }

    "malformed PSI line" - {

        "a PSI line missing its tagged fields degrades to the sentinel" in {
            val (bytes, len) = span("some \n")
            val psi          = PsiHandles(Stat.initScope("lptest-missing-tags"), MachineHandles.nanosPerSecFor(8L))
            LinuxPressure.observeLine(bytes, len, LinuxPressure.SomeLine, psi.cpuSome)
            assert(!gaugeRegistered("lptest-missing-tags", "cpu", "some", "avg10"))
            assert(!histogramRegistered("lptest-missing-tags", "cpu", "some", "rate"))
        }
    }

end LinuxPressureTest
