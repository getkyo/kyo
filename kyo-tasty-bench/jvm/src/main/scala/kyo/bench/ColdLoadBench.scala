package kyo.bench

import java.nio.file.Files
import java.nio.file.Paths
import kyo.*

/** Standalone cold-load profiling harness for kyo-tasty (W11).
  *
  * Opens the kyo-bench compiled TASTy directory via Tasty.Classpath.open, runs 5 warmup + 10 measurement iterations, and prints median/p95
  * to stdout. Designed to be run with async-profiler attached via -agentpath.
  *
  * Run with: sbt -J-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/cold-load-flame.html \
  * 'kyo-tasty-bench/runMain kyo.bench.ColdLoadProfile'
  */
object ColdLoadProfile:

    private val kyoBenchRoot =
        "/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-bench/.jvm/target/scala-3.8.3/classes"

    private def timeNs(action: => Unit): Long =
        val t0 = java.lang.System.nanoTime()
        action
        java.lang.System.nanoTime() - t0
    end timeNs

    private def bench(name: String, warmup: Int, measure: Int)(action: => Unit): Array[Long] =
        for _ <- 1 to warmup do action
        val times = new Array[Long](measure)
        for i <- 0 until measure do times(i) = timeNs(action)
        java.util.Arrays.sort(times)
        val median = times(measure / 2)
        val p95    = times((measure * 95 / 100).min(measure - 1))
        java.lang.System.out.println(
            f"[$name] median=${median / 1_000_000.0}%.2f ms  p95=${p95 / 1_000_000.0}%.2f ms"
        )
        times
    end bench

    private def runSync[A](v: => A < (Async & Scope & Abort[TastyError]))(using AllowUnsafe, Frame): A =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Abort.run[TastyError](v).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new RuntimeException(s"ColdLoadProfile failed: $err")
            case Result.Panic(ex)    => throw ex
        }) match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw err
            case Result.Panic(ex)    => throw ex

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger

        val warmupIter  = 5
        val measureIter = args.headOption.flatMap(_.toIntOption).getOrElse(10)

        // Resolve the actual root — handle path aliases
        val rootCandidates = Seq(
            kyoBenchRoot,
            // Resolve via the worktree link from the current working directory
            Paths.get("").toAbsolutePath.resolve(
                "kyo-bench/.jvm/target/scala-3.8.3/classes"
            ).toString
        )
        val root = rootCandidates.find(p => Files.isDirectory(Paths.get(p))).getOrElse {
            java.lang.System.err.println(s"ERROR: kyo-bench classes directory not found. Tried:")
            rootCandidates.foreach(r => java.lang.System.err.println(s"  $r"))
            java.lang.System.err.println("Run: sbt 'kyo-bench/compile' first")
            java.lang.System.exit(1)
            ""
        }

        // Count TASTy and class files, total bytes
        val tastyFiles = Files.walk(Paths.get(root)).filter(_.toString.endsWith(".tasty")).toArray.length
        val classFiles = Files.walk(Paths.get(root)).filter(_.toString.endsWith(".class")).toArray.length
        val totalBytes = Files.walk(Paths.get(root))
            .filter(p => p.toString.endsWith(".tasty") || p.toString.endsWith(".class"))
            .mapToLong(p =>
                try Files.size(p)
                catch case _: Throwable => 0L
            )
            .sum()
        val totalMB = totalBytes.toDouble / (1024 * 1024)

        java.lang.System.out.println("=== ColdLoadProfile — W11 kyo-bench cold-load ===")
        java.lang.System.out.println(s"Root: $root")
        java.lang.System.out.println(f"Files: $tastyFiles TASTy + $classFiles classfiles, ${totalMB}%.2f MB")
        java.lang.System.out.println()

        val times = bench("W11 cold-load kyo-bench (enumerate top-level classes)", warmupIter, measureIter):
            val _ = runSync:
                Scope.run:
                    Tasty.Classpath.open(Seq(root)).map(_.topLevelClasses.size)

        java.lang.System.out.println()

        // Also run snapshot-write timing
        val tmpDir = Files.createTempDirectory("kyo-tasty-profile").toString
        val snapshotTimes = bench("W11b cold-load kyo-bench + snapshot write", warmupIter, measureIter):
            val _ = runSync:
                Scope.run:
                    Tasty.Classpath.openCached(Seq(root), tmpDir).map(_.topLevelClasses.size)

        java.lang.System.out.println()
        java.lang.System.out.println("=== Summary ===")
        java.lang.System.out.println(
            f"W11  median=${times(measureIter / 2) / 1_000_000.0}%.2f ms  p95=${times((measureIter * 95 / 100).min(measureIter - 1)) / 1_000_000.0}%.2f ms"
        )
        java.lang.System.out.println(
            f"W11b median=${snapshotTimes(measureIter / 2) / 1_000_000.0}%.2f ms  p95=${snapshotTimes((measureIter * 95 / 100).min(measureIter - 1)) / 1_000_000.0}%.2f ms"
        )
        java.lang.System.out.println("=== done ===")
    end main

end ColdLoadProfile
