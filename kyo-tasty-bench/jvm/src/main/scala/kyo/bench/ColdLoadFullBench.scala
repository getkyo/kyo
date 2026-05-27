package kyo.bench

import java.nio.file.Files
import java.nio.file.Paths
import kyo.*

/** Cold-load profiling harness — FULL kyo-bench classpath (121 jars + kyo-bench classes directory).
  *
  * Reads the classpath from /tmp/kyo-bench-cp.txt (produced by: sbt 'show kyo-bench/fullClasspath' 2>&1 | grep -oE '/[^ ]*\.jar' | sort -u
  * > /tmp/kyo-bench-cp.txt ) and adds the kyo-bench compiled-classes directory as an additional root.
  *
  * Runs 3 warmup + 5 measurement iterations (cold load at this scale is 1-5s each).
  *
  * Run baseline: sbt 'kyo-tasty-bench/runMain kyo.bench.ColdLoadFullBench'
  *
  * Run with CPU profiling: sbt -J-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/full-flame.html \
  * 'kyo-tasty-bench/runMain kyo.bench.ColdLoadFullBench'
  *
  * Run with allocation profiling: sbt -J-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=alloc,file=/tmp/full-alloc.html \
  * 'kyo-tasty-bench/runMain kyo.bench.ColdLoadFullBench'
  */
object ColdLoadFullBench:

    private val cpFile =
        "/tmp/kyo-bench-cp.txt"

    private val kyoBenchClassesDir =
        "/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-bench/.jvm/target/scala-3.8.3/classes"

    private def timeNs(action: => Unit): Long =
        val t0 = java.lang.System.nanoTime()
        action
        java.lang.System.nanoTime() - t0
    end timeNs

    private def bench(name: String, warmup: Int, measure: Int)(action: => Unit): Array[Long] =
        java.lang.System.out.println(s"  warmup ($warmup iters)...")
        for i <- 1 to warmup do
            java.lang.System.out.print(s"    warmup $i/$warmup... ")
            val t = timeNs(action)
            java.lang.System.out.println(f"${t / 1_000_000.0}%.0f ms")
        end for
        java.lang.System.out.println(s"  measuring ($measure iters)...")
        val times = new Array[Long](measure)
        for i <- 0 until measure do
            java.lang.System.out.print(s"    iter ${i + 1}/$measure... ")
            times(i) = timeNs(action)
            java.lang.System.out.println(f"${times(i) / 1_000_000.0}%.0f ms")
        end for
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
            case Result.Failure(err) => throw new RuntimeException(s"ColdLoadFullBench failed: $err")
            case Result.Panic(ex)    => throw ex
        }) match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw err
            case Result.Panic(ex)    => throw ex

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger

        val warmupIter  = args.lift(0).flatMap(_.toIntOption).getOrElse(5)
        val measureIter = args.lift(1).flatMap(_.toIntOption).getOrElse(10)

        // Load jar paths from /tmp/kyo-bench-cp.txt
        val cpFilePath = Paths.get(cpFile)
        if !Files.isRegularFile(cpFilePath) then
            java.lang.System.err.println(s"ERROR: classpath file not found: $cpFile")
            java.lang.System.err.println(
                "Run: sbt 'show kyo-bench/fullClasspath' 2>&1 | grep -oE '/[^ ]*\\.jar' | sort -u > /tmp/kyo-bench-cp.txt"
            )
            java.lang.System.exit(1)
        end if

        val jarPaths = new java.util.ArrayList[String]()
        Files.lines(cpFilePath).forEach: line =>
            val trimmed = line.trim
            if trimmed.nonEmpty && Files.isRegularFile(Paths.get(trimmed)) then
                val _ = jarPaths.add(trimmed)

        // Add kyo-bench classes directory if it exists
        val classesDir = kyoBenchClassesDir
        val allRoots: Seq[String] =
            val roots = scala.collection.mutable.ArrayBuffer.empty[String]
            roots ++= jarPaths.toArray(Array.empty[String]).toSeq
            if Files.isDirectory(Paths.get(classesDir)) then
                roots += classesDir
            roots.toSeq
        end allRoots

        // Count files for reporting
        import scala.jdk.CollectionConverters.*
        var tastyCount = 0L
        var classCount = 0L
        var totalBytes = 0L

        // Count in kyo-bench classes dir
        if Files.isDirectory(Paths.get(classesDir)) then
            Files.walk(Paths.get(classesDir)).iterator().asScala.foreach: p =>
                if p.toString.endsWith(".tasty") then
                    tastyCount += 1
                    totalBytes += Files.size(p)
                else if p.toString.endsWith(".class") then
                    classCount += 1
                    totalBytes += Files.size(p)
        end if

        // Count in jars (uncompressed sizes)
        for jar <- jarPaths.toArray(Array.empty[String]) do
            try
                val jf = new java.util.jar.JarFile(jar)
                try
                    jf.entries().asIterator().asScala.foreach: e =>
                        if e.getName.endsWith(".tasty") then
                            tastyCount += 1
                            totalBytes += e.getSize
                        else if e.getName.endsWith(".class") then
                            classCount += 1
                            totalBytes += e.getSize
                finally jf.close()
                end try
            catch
                case _: Throwable => ()
        end for

        val totalMB = totalBytes.toDouble / (1024 * 1024)

        java.lang.System.out.println("=== ColdLoadFullBench — FULL kyo-bench classpath ===")
        java.lang.System.out.println(s"Jars: ${jarPaths.size()}")
        java.lang.System.out.println(s"  + kyo-bench classes dir: $classesDir")
        java.lang.System.out.println(s"Total roots: ${allRoots.size}")
        java.lang.System.out.println(f"TASTy files: $tastyCount, class files: $classCount, uncompressed: $totalMB%.2f MB")
        java.lang.System.out.println()

        // --- Uninstrumented baseline ---
        java.lang.System.out.println("=== W11-full: cold-load full classpath (no snapshot) ===")
        val times = bench("W11-full cold-load (full classpath)", warmupIter, measureIter):
            val _ = runSync:
                Scope.run:
                    Tasty.Classpath.open(allRoots).map(_.topLevelClasses.size)

        java.lang.System.out.println()

        // --- Snapshot path ---
        java.lang.System.out.println("=== W11b-full: cold-load full classpath + snapshot cache ===")
        val tmpDir = Files.createTempDirectory("kyo-tasty-full-profile").toString
        val snapshotTimes = bench("W11b-full cold-load + snapshot (full classpath)", warmupIter, measureIter):
            val _ = runSync:
                Scope.run:
                    Tasty.Classpath.openCached(allRoots, tmpDir).map(_.topLevelClasses.size)

        java.lang.System.out.println()
        java.lang.System.out.println("=== Summary ===")
        java.lang.System.out.println(
            f"W11-full  median=${times(measureIter / 2) / 1_000_000.0}%.2f ms  p95=${times((measureIter * 95 / 100).min(measureIter - 1)) / 1_000_000.0}%.2f ms"
        )
        java.lang.System.out.println(
            f"W11b-full median=${snapshotTimes(measureIter / 2) / 1_000_000.0}%.2f ms  p95=${snapshotTimes((measureIter * 95 / 100).min(measureIter - 1)) / 1_000_000.0}%.2f ms"
        )
        java.lang.System.out.println("=== done ===")

        // If OTLP export is active, sleep briefly before exit to allow the background batch-flush
        // fiber one full export cycle. OTLPTraceExporter.shutdown is private[otlp] so the accessible
        // fallback is Thread.sleep. The default bspScheduleDelay is 5 s so 6 s here is sufficient
        // to capture the final batch.
        if java.lang.System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT") != null then
            java.lang.System.out.println("[OTLP] waiting for background span flush...")
            Thread.sleep(6000)
            java.lang.System.out.println("[OTLP] flush wait complete")
        end if
    end main

end ColdLoadFullBench
