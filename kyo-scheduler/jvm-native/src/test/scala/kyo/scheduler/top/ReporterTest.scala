package kyo.scheduler.top

import java.io.File
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AnyFreeSpec
import scala.io.Source

class ReporterTest extends AnyFreeSpec with NonImplicitAssertions {

    def reg = RegulatorStatus(0, 0.0, 0.0, 0, 0, 0, 0)

    def status = Status(
        currentWorkers = 1,
        allocatedWorkers = 2,
        loadAvg = 0.5,
        flushes = 0,
        activeThreads = 1,
        totalThreads = 2,
        workers = Seq(WorkerStatus(0, true, "w0", "", false, false, 5L, 0L, 3L, 0L, 0L, 1, 0L)),
        admission = AdmissionStatus(100, 0, 0, reg),
        concurrency = ConcurrencyStatus(reg)
    )

    def read(path: String): String = {
        val f = new File(path)
        if (!f.exists()) ""
        else {
            val s = Source.fromFile(f)
            try s.mkString
            finally s.close()
        }
    }

    "topStatusFile sink" - {
        "writes the compact status line on its dedicated thread" in {
            val path = File.createTempFile("kyo-sched-reporter", ".status").getAbsolutePath
            val reporter =
                new Reporter(() => status, enableTopJMX = false, enableTopConsoleMs = 0, topStatusFile = path, topStatusFileMs = 10)
            try {
                var line     = ""
                val deadline = System.currentTimeMillis() + 5000
                while (line.isEmpty && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10)
                    line = read(path).trim
                }
                assert(line.startsWith("kyo.sched"))
                assert(line.contains(" cur=1 alloc=2"))
                assert(line.contains(" exec=5 done=3"))
                assert(line.contains(" admit=100"))
            } finally reporter.close()
        }

        "starts no sink and writes nothing when the path is empty" in {
            val path = File.createTempFile("kyo-sched-reporter-off", ".status").getAbsolutePath
            new File(path).delete()
            val reporter =
                new Reporter(() => status, enableTopJMX = false, enableTopConsoleMs = 0, topStatusFile = "", topStatusFileMs = 10)
            try assert(!new File(path).exists()) // no executor is created, so nothing is ever written
            finally reporter.close()
        }
    }
}
