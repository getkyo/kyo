package kyobench

import java.io.File
import java.nio.file.Files

/** Compiles each fixture file with an in-process dotc against each kernel's classes and
  * reports wall time per compile. The corpus classpath is this process's own classpath
  * (kyo-data and its dependencies, no kernel) plus one kernel's classes directory, so the
  * measured text is identical and only the kernel varies. Measurements interleave the two
  * kernels so JIT and machine drift cancel; a compile with diagnostics fails the run, so a
  * broken fixture cannot masquerade as a fast one.
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

    def main(args: Array[String]): Unit =
        val warmupRounds  = if args.length > 0 then args(0).toInt else 8
        val measureRounds = if args.length > 1 then args(1).toInt else 15

        val fixturesDir = new File(root, "kyo-compile-bench/fixtures")
        val fixtures    = fixturesDir.listFiles().filter(_.getName.endsWith(".scala")).sortBy(_.getName)
        require(fixtures.nonEmpty, s"no fixtures under ${fixturesDir.getAbsolutePath}")

        val baseCp = sys.props("java.class.path")
        kernels.foreach(k => require(new File(k.classesDir).isDirectory, s"missing kernel classes: ${k.classesDir}"))

        def compile(fixture: File, kernel: Kernel, out: File): Long =
            val cpArg = baseCp + File.pathSeparator + kernel.classesDir
            val args  = Array("-classpath", cpArg, "-d", out.getAbsolutePath, fixture.getAbsolutePath)
            val start = System.nanoTime()
            val rep   = dotty.tools.dotc.Main.process(args)
            val nanos = System.nanoTime() - start
            require(!rep.hasErrors, s"${fixture.getName} failed against ${kernel.name} kernel")
            nanos
        end compile

        val outDirs = kernels.map(k => k -> Files.createTempDirectory(s"kyocb-${k.name}").toFile).toMap

        println(f"${"fixture"}%-24s ${"old ms"}%10s ${"new ms"}%10s ${"ratio"}%7s   (mean of $measureRounds, min in parens)")
        val results =
            for fixture <- fixtures.toList yield
                for _ <- 1 to warmupRounds; k <- kernels do compile(fixture, k, outDirs(k))
                val samples =
                    (for round <- 1 to measureRounds yield
                        // alternate kernel order per round so JIT and machine
                        // drift within a round cancel across the run
                        val order = if round % 2 == 0 then kernels.reverse else kernels
                        order.map(k => k -> compile(fixture, k, outDirs(k))).toMap
                    ).toList
                val stats = kernels.map { k =>
                    val xs = samples.map(_(k))
                    k.name -> (xs.sum.toDouble / xs.size / 1e6, xs.min.toDouble / 1e6)
                }.toMap
                val (oldMean, oldMin) = stats("old")
                val (newMean, newMin) = stats("new")
                println(f"${fixture.getName}%-24s $oldMean%7.1f ($oldMin%5.1f) $newMean%7.1f ($newMin%5.1f) ${newMean / oldMean}%7.3f")
                (fixture.getName, oldMean, newMean)

        val totalOld = results.map(_._2).sum
        val totalNew = results.map(_._3).sum
        println(f"${"TOTAL"}%-24s $totalOld%7.1f         $totalNew%7.1f         ${totalNew / totalOld}%7.3f")
    end main

end Runner
