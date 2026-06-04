package kyo.bench

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kyo.*
import tastyquery.Classpaths.ClasspathEntry
import tastyquery.Contexts.Context
import tastyquery.Symbols.ClassSymbol
import tastyquery.Symbols.PackageSymbol
import tastyquery.jdk.ClasspathLoaders

/** Side-by-side cold-load comparison: kyo-tasty vs tasty-query 1.7.0.
  *
  * Parameterized over three classpath sizes (small / medium / large) cut from the kyo-bench full classpath at /tmp/kyo-bench-cp.txt. Each
  * iteration constructs a fresh classpath/context from scratch (no cross-iteration caching of Tasty.Classpath, no reuse of tasty-query
  * Context). OS page cache and JIT compile state will be warm after the first warmup iter — that benefits both libraries equally.
  *
  * Run: sbt 'kyo-tasty-bench/runMain kyo.bench.TastyQueryCompareBench'
  *
  * Optional args: warmupIter measureIter (defaults: 3 5)
  */
object TastyQueryCompareBench:

    private val cpFile =
        "/tmp/kyo-bench-cp.txt"

    private val kyoBenchClassesDir =
        "/Users/fwbrasil/workspace/kyo/.claude/worktrees/cached-inventing-quasar/kyo-bench/.jvm/target/scala-3.8.3/classes"

    final private case class Size(label: String, jarCount: Int, includeClassesDir: Boolean)

    private val sizes = Seq(
        Size("small", 10, includeClassesDir = false),
        Size("medium", 40, includeClassesDir = false),
        Size("large", Int.MaxValue, includeClassesDir = true)
    )

    private def timeNs(action: => Unit): Long =
        val t0 = java.lang.System.nanoTime()
        action
        java.lang.System.nanoTime() - t0
    end timeNs

    final private case class Stats(median: Double, p95: Double)

    private def bench(label: String, warmup: Int, measure: Int)(action: => Unit): Stats =
        for i <- 1 to warmup do
            val t = timeNs(action)
            java.lang.System.out.println(f"    [warmup $i/$warmup] $label: ${t / 1_000_000.0}%.0f ms")
        val times = new Array[Long](measure)
        for i <- 0 until measure do
            times(i) = timeNs(action)
            java.lang.System.out.println(f"    [iter ${i + 1}/$measure] $label: ${times(i) / 1_000_000.0}%.0f ms")
        java.util.Arrays.sort(times)
        val median = times(measure / 2) / 1_000_000.0
        val p95    = times((measure * 95 / 100).min(measure - 1)) / 1_000_000.0
        Stats(median, p95)
    end bench

    private def runSync[A](v: => A < (Async & Abort[TastyError]))(using AllowUnsafe, Frame): A =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Abort.run[TastyError](v).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new RuntimeException(s"TastyQueryCompareBench failed: $err")
            case Result.Panic(ex)    => throw ex
        }) match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw err
            case Result.Panic(ex)    => throw ex

    /** Force tasty-query to enumerate all top-level classes by recursively walking packages from the root. PackageSymbol.declarations takes
      * the Context implicitly and triggers TASTy/classfile parsing for that package's contents. Any per-package decode failure (e.g.
      * tasty-query 1.7.0 vs scala-library 3.8.3 unrecognized TASTy tag) is counted, not thrown — so the bench reports a meaningful number
      * for the largest classpath even when a few files trip a format-skew bug.
      */
    private def walkAllTopLevel(ctx: Context): (Int, Int) =
        given Context = ctx
        var count     = 0
        var failures  = 0
        def visit(pkg: PackageSymbol): Unit =
            val decls: scala.collection.immutable.List[tastyquery.Symbols.Symbol] =
                try pkg.declarations
                catch
                    case _: Throwable =>
                        failures += 1
                        Nil
            val it = decls.iterator
            while it.hasNext do
                it.next() match
                    case sub: PackageSymbol => visit(sub)
                    case _: ClassSymbol     => count += 1
                    case _                  => ()
            end while
        end visit
        visit(ctx.defn.RootPackage)
        (count, failures)
    end walkAllTopLevel

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger

        val warmupIter  = args.lift(0).flatMap(_.toIntOption).getOrElse(3)
        val measureIter = args.lift(1).flatMap(_.toIntOption).getOrElse(5)

        val cpFilePath = Paths.get(cpFile)
        if !Files.isRegularFile(cpFilePath) then
            java.lang.System.err.println(s"ERROR: classpath file not found: $cpFile")
            java.lang.System.err.println(
                "Run: sbt 'show kyo-bench/fullClasspath' 2>&1 | grep -oE '/[^ ]*\\.jar' | sort -u > /tmp/kyo-bench-cp.txt"
            )
            java.lang.System.exit(1)
        end if

        val allJars = scala.collection.mutable.ArrayBuffer.empty[String]
        Files.lines(cpFilePath).forEach: line =>
            val trimmed = line.trim
            if trimmed.nonEmpty && Files.isRegularFile(Paths.get(trimmed)) then
                allJars += trimmed

        java.lang.System.out.println("=== TastyQueryCompareBench — kyo-tasty vs tasty-query 1.7.0 ===")
        java.lang.System.out.println(s"Source classpath: ${allJars.size} jars from $cpFile")
        java.lang.System.out.println(s"warmupIter=$warmupIter  measureIter=$measureIter")
        java.lang.System.out.println()

        final case class Row(size: String, jarCount: Int, kyoStats: Stats, tqStats: Stats, tqTopLevel: Int, tqFailures: Int)

        val rows = scala.collection.mutable.ArrayBuffer.empty[Row]

        for size <- sizes do
            val jars         = allJars.take(size.jarCount).toSeq
            val classesDirIn = size.includeClassesDir && Files.isDirectory(Paths.get(kyoBenchClassesDir))
            val rootStrings  = if classesDirIn then jars :+ kyoBenchClassesDir else jars
            val rootPaths    = rootStrings.map(Paths.get(_)).toList

            java.lang.System.out.println(s"--- size=${size.label}  jars=${jars.size}  classesDir=$classesDirIn ---")

            java.lang.System.out.println(s"  [kyo-tasty]")
            val kyoStats = bench(s"kyo-tasty ${size.label}", warmupIter, measureIter):
                val _ = runSync:
                    Tasty.withClasspath(rootStrings):
                        Tasty.classpath.map(_.topLevelClasses.size)

            java.lang.System.out.println(s"  [tasty-query]")
            var lastCount    = 0
            var lastFailures = 0
            val tqStats = bench(s"tasty-query ${size.label}", warmupIter, measureIter):
                val entries: List[ClasspathEntry] = ClasspathLoaders.read(rootPaths)
                val ctx: Context                  = Context.initialize(entries)
                val (c, f)                        = walkAllTopLevel(ctx)
                lastCount = c
                lastFailures = f

            rows += Row(size.label, jars.size, kyoStats, tqStats, lastCount, lastFailures)
            java.lang.System.out.println()
        end for

        java.lang.System.out.println("=== Summary ===")
        java.lang.System.out.println(
            f"${"size"}%-8s ${"jars"}%5s | ${"kyo-tasty med"}%14s ${"p95"}%9s | ${"tasty-query med"}%16s ${"p95"}%9s | ${"ratio (kt/tq)"}%14s | tq-classes tq-fail"
        )
        for r <- rows do
            val ratio = r.kyoStats.median / r.tqStats.median
            java.lang.System.out.println(
                f"${r.size}%-8s ${r.jarCount}%5d | ${r.kyoStats.median}%11.2f ms ${r.kyoStats.p95}%6.2f ms | ${r.tqStats.median}%13.2f ms ${r.tqStats.p95}%6.2f ms | ${ratio}%13.2fx | ${r.tqTopLevel}%10d ${r.tqFailures}%7d"
            )
        end for
        java.lang.System.out.println("=== done ===")
    end main

end TastyQueryCompareBench
