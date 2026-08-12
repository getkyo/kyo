package kyobench

import java.io.File
import java.nio.file.Files

/** Compiles each fixture file with an in-process dotc against each kernel's classes and
  * reports wall time per compile. The corpus classpath is this process's own classpath
  * (kyo-data and its dependencies, no kernel) plus one kernel's classes directory, so the
  * measured text is identical and only the kernel varies. Measurements interleave the two
  * kernels with alternating order per round so JIT and machine drift cancel; a compile with
  * diagnostics fails the run, so a broken fixture cannot masquerade as a fast one.
  *
  * Observability: every compile logs a [run] line when it finishes, and a heartbeat thread
  * logs what is in flight every ten seconds, so a hang is visible as a heartbeat whose
  * elapsed time keeps growing on the same compile. Fixtures whose first warmup exceeds a
  * few seconds scale their rounds down, logged as [scale].
  */
object Runner:

    final case class Kernel(name: String, classesDir: String)

    // the forked run starts in the project directory; the repo root is the
    // closest ancestor holding the fixtures
    val root: File =
        Iterator.iterate(new File(".").getAbsoluteFile)(_.getParentFile)
            .takeWhile(_ != null)
            .find(d => new File(d, "kyo-compile-bench/fixtures").isDirectory)
            .getOrElse(sys.error("repo root not found from " + new File(".").getAbsolutePath))

    val kernels = List(
        Kernel("old", new File(root, "kyo-kernel/jvm/target/scala-3.8.4/classes").getAbsolutePath),
        Kernel("new", new File(root, "kyo-kernel2/jvm/target/scala-3.8.4/classes").getAbsolutePath)
    )

    @volatile private var inFlight: String    = ""
    @volatile private var inFlightStart: Long = 0L

    private def log(line: String): Unit =
        println(line)
        System.out.flush()

    def main(args: Array[String]): Unit =
        val warmupRounds  = if args.length > 0 then args(0).toInt else 6
        val measureRounds = if args.length > 1 then args(1).toInt else 12

        val fixturesDir = new File(root, "kyo-compile-bench/fixtures")
        val fixtures    = fixturesDir.listFiles().filter(_.getName.endsWith(".scala")).sortBy(_.getName)
        require(fixtures.nonEmpty, s"no fixtures under ${fixturesDir.getAbsolutePath}")

        val baseCp = sys.props("java.class.path")
        kernels.foreach(k => require(new File(k.classesDir).isDirectory, s"missing kernel classes: ${k.classesDir}"))

        val heartbeat = new Thread(() =>
            while true do
                Thread.sleep(10000)
                val s = inFlight
                if s.nonEmpty then
                    val secs = (System.nanoTime() - inFlightStart) / 1e9
                    log(f"[heartbeat] $s in flight for $secs%.1f s")
        )
        heartbeat.setDaemon(true)
        heartbeat.start()

        def compile(fixture: File, kernel: Kernel, out: File, phase: String, round: Int, rounds: Int): Long =
            val cpArg = baseCp + File.pathSeparator + kernel.classesDir
            val args  = Array("-classpath", cpArg, "-d", out.getAbsolutePath, fixture.getAbsolutePath)
            inFlight = s"${fixture.getName} ${kernel.name} $phase $round/$rounds"
            inFlightStart = System.nanoTime()
            val rep   = dotty.tools.dotc.Main.process(args)
            val nanos = System.nanoTime() - inFlightStart
            inFlight = ""
            log(f"[run] ${fixture.getName}%-24s ${kernel.name}%-4s $phase%-7s $round/$rounds ${nanos / 1e6}%8.1f ms")
            require(!rep.hasErrors, s"${fixture.getName} failed against ${kernel.name} kernel")
            nanos
        end compile

        val outDirs = kernels.map(k => k -> Files.createTempDirectory(s"kyocb-${k.name}").toFile).toMap

        val results =
            for fixture <- fixtures.toList yield
                // the first warmup prices the fixture; expensive ones run fewer rounds
                val probe = kernels.map(k => compile(fixture, k, outDirs(k), "warmup", 1, warmupRounds)).max
                val (wr, mr) =
                    if probe > 3_000_000_000L then (math.max(1, warmupRounds / 3 - 1), math.max(4, measureRounds / 3))
                    else (warmupRounds - 1, measureRounds)
                if wr != warmupRounds - 1 then
                    log(s"[scale] ${fixture.getName} first warmup ${probe / 1_000_000} ms: scaling to $wr more warmups, $mr measures")
                for r <- 1 to wr; k <- kernels do compile(fixture, k, outDirs(k), "warmup", r + 1, wr + 1)
                val samples =
                    (for round <- 1 to mr yield
                        // alternate kernel order per round so drift within a
                        // round cancels across the run
                        val order = if round % 2 == 0 then kernels.reverse else kernels
                        order.map(k => k -> compile(fixture, k, outDirs(k), "measure", round, mr)).toMap
                    ).toList
                val stats = kernels.map { k =>
                    val xs = samples.map(_(k))
                    k.name -> (xs.sum.toDouble / xs.size / 1e6, xs.min.toDouble / 1e6)
                }.toMap
                val (oldMean, oldMin) = stats("old")
                val (newMean, newMin) = stats("new")
                log(
                    f"[row] ${fixture.getName}%-24s old $oldMean%7.1f ($oldMin%5.1f) new $newMean%7.1f ($newMin%5.1f) ratio ${newMean / oldMean}%5.3f"
                )
                (fixture.getName, oldMean, newMean)

        log(f"${"fixture"}%-24s ${"old ms"}%10s ${"new ms"}%10s ${"ratio"}%7s")
        results.foreach((n, o, nw) => log(f"$n%-24s $o%10.1f $nw%10.1f ${nw / o}%7.3f"))
        val totalOld = results.map(_._2).sum
        val totalNew = results.map(_._3).sum
        log(f"${"TOTAL"}%-24s $totalOld%10.1f $totalNew%10.1f ${totalNew / totalOld}%7.3f")
    end main

end Runner
