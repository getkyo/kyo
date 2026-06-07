package kyo.bench

import kyo.*
import tastyquery.Classpaths.ClasspathEntry
import tastyquery.Contexts.Context
import tastyquery.Symbols.ClassSymbol
import tastyquery.Symbols.PackageSymbol
import tastyquery.jdk.ClasspathLoaders

/** Side-by-side cold-load comparison: kyo-tasty vs tasty-query 1.7.0.
  *
  * Parameterized over three classpath sizes (small / medium / large) cut from the kyo-bench full
  * classpath at /tmp/kyo-bench-cp.txt. Each iteration constructs a fresh classpath/context from
  * scratch (no cross-iteration caching of Tasty.Classpath, no reuse of tasty-query Context). OS
  * page cache and JIT compile state will be warm after the first warmup iter; that benefits both
  * libraries equally.
  *
  * The kyo-bench classes directory is resolved in order:
  *   1. The KYO_BENCH_CLASSES_DIR environment variable (set to the absolute path of the
  *      kyo-bench JVM classes directory).
  *   2. A CWD-relative fallback: kyo-bench/.jvm/target/scala-3.8.3/classes
  *
  * Run: sbt 'kyo-tasty-bench/runMain kyo.bench.TastyQueryCompareBench'
  *
  * Optional args: warmupIter measureIter (defaults: 3 5)
  */
object TastyQueryCompareBench extends KyoApp:

    private val cpFile = "/tmp/kyo-bench-cp.txt"

    final private case class Size(label: String, jarCount: Int, includeClassesDir: Boolean)

    private val sizes = Seq(
        Size("small", 10, includeClassesDir = false),
        Size("medium", 40, includeClassesDir = false),
        Size("large", Int.MaxValue, includeClassesDir = true)
    )

    /** Time one call to action and return the elapsed Duration. */
    private def timed(action: => Unit < Sync)(using Frame): Duration < Sync =
        Clock.nowMonotonic.map { start =>
            action.map { _ =>
                Clock.nowMonotonic.map { end =>
                    end - start
                }
            }
        }

    final private case class Stats(median: Double, p95: Double)

    private def bench(label: String, warmup: Int, measure: Int)(action: => Unit < Sync)(using Frame): Stats < Sync =
        Kyo.foreach(Chunk.from(1 to warmup)) { i =>
            timed(action).map { d =>
                Console.printLine(f"    [warmup $i/$warmup] $label: ${d.toNanos / 1_000_000.0}%.0f ms")
            }
        }.map { _ =>
            Kyo.foreach(Chunk.from(0 until measure)) { i =>
                timed(action).map { d =>
                    Console.printLine(f"    [iter ${i + 1}/$measure] $label: ${d.toNanos / 1_000_000.0}%.0f ms").map { _ =>
                        d
                    }
                }
            }.map { durations =>
                val sorted = durations.sortBy(_.toNanos)
                val median = sorted(measure / 2).toNanos / 1_000_000.0
                val p95    = sorted((measure * 95 / 100).min(measure - 1)).toNanos / 1_000_000.0
                Stats(median, p95)
            }
        }

    private def runSync[A](v: => A < (Async & Abort[TastyError]))(using AllowUnsafe, Frame): A =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Abort.run[TastyError](v).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new RuntimeException(s"TastyQueryCompareBench failed: $err")
            case Result.Panic(ex)    => throw ex
        }) match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw err
            case Result.Panic(ex)    => throw ex

    /** Force tasty-query to enumerate all top-level classes by recursively walking packages from
      * the root. PackageSymbol.declarations takes the Context implicitly and triggers
      * TASTy/classfile parsing for that package's contents. Any per-package decode failure (e.g.
      * tasty-query 1.7.0 vs scala-library 3.8.3 unrecognized TASTy tag) is counted, not thrown
      * so the bench reports a meaningful number for the largest classpath even when a few files
      * trip a format-skew bug.
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

    /** Resolve the kyo-bench classes directory. Reads KYO_BENCH_CLASSES_DIR env var first, then
      * falls back to a CWD-relative path.
      */
    private def resolveClassesDir(): String < Sync =
        System.env[String]("KYO_BENCH_CLASSES_DIR").map {
            case Present(dir) => dir
            case Absent =>
                Path.cwd.map { cwd =>
                    (cwd / "kyo-bench" / ".jvm" / "target" / "scala-3.8.3" / "classes").toString
                }
        }

    final private case class Row(
        size: String,
        jarCount: Int,
        kyoStats: Stats,
        tqStats: Stats,
        tqTopLevel: Int,
        tqFailures: Int
    )

    private def benchSize(
        size: Size,
        allJars: Chunk[String],
        kyoBenchClassesDir: String,
        warmupIter: Int,
        measureIter: Int
    )(using AllowUnsafe, Frame): Row < Sync =
        val jars = allJars.take(size.jarCount).toSeq
        Path(kyoBenchClassesDir).isDirectory.map { classesDirExists =>
            val classesDirIn = size.includeClassesDir && classesDirExists
            val rootStrings  = if classesDirIn then jars :+ kyoBenchClassesDir else jars
            val rootPaths    = rootStrings.map(s => java.nio.file.Paths.get(s)).toList

            Console.printLine(s"--- size=${size.label}  jars=${jars.size}  classesDir=$classesDirIn ---")
                .andThen(Console.printLine(s"  [kyo-tasty]"))
                .andThen {
                    bench(s"kyo-tasty ${size.label}", warmupIter, measureIter)(Sync.defer {
                        val _ = runSync {
                            Tasty.withClasspath(rootStrings) {
                                Tasty.classpath.map(_.topLevelClasses.size)
                            }
                        }
                    })
                }
                .map { kyoStats =>
                    Console.printLine(s"  [tasty-query]")
                        .andThen {
                            // Use a mutable cell to collect the last tq result (count, failures)
                            // from the bench action. The cell is allocated in Sync.defer so that
                            // its lifetime is tracked in the effect row, not captured at object
                            // construction time.
                            Sync.defer(new Array[Int](2)).map { cell =>
                                bench(s"tasty-query ${size.label}", warmupIter, measureIter)(Sync.defer {
                                    val entries: List[ClasspathEntry] = ClasspathLoaders.read(rootPaths)
                                    val ctx: Context                  = Context.initialize(entries)
                                    val (c, f)                        = walkAllTopLevel(ctx)
                                    cell(0) = c
                                    cell(1) = f
                                }).map { tqStats =>
                                    Console.printLine("")
                                        .map { _ =>
                                            Row(size.label, jars.size, kyoStats, tqStats, cell(0), cell(1))
                                        }
                                }
                            }
                        }
                }
        }
    end benchSize

    run {
        import AllowUnsafe.embrace.danger

        val warmupIter  = args.lift(0).flatMap(_.toIntOption).getOrElse(3)
        val measureIter = args.lift(1).flatMap(_.toIntOption).getOrElse(5)

        Path(cpFile).isRegularFile.map { cpExists =>
            if !cpExists then
                Console.printLineErr(s"ERROR: classpath file not found: $cpFile")
                    .andThen(Console.printLineErr(
                        "Run: sbt 'show kyo-bench/fullClasspath' 2>&1 | grep -oE '/[^ ]*\\.jar' | sort -u > /tmp/kyo-bench-cp.txt"
                    ))
                    .andThen(Sync.defer(exit(1)))
            else
                Path(cpFile).readLines.map { rawLines =>
                    val candidates = rawLines.filter(_.trim.nonEmpty).map(_.trim)
                    Kyo.foreach(candidates) { trimmed =>
                        Path(trimmed).isRegularFile.map { exists =>
                            if exists then Present(trimmed) else Absent
                        }
                    }.map { maybes =>
                        val allJars = maybes.flatMap(_.toChunk)
                        resolveClassesDir().map { kyoBenchClassesDir =>
                            Console.printLine("=== TastyQueryCompareBench: kyo-tasty vs tasty-query 1.7.0 ===")
                                .andThen(Console.printLine(s"Source classpath: ${allJars.size} jars from $cpFile"))
                                .andThen(Console.printLine(s"warmupIter=$warmupIter  measureIter=$measureIter"))
                                .andThen(Console.printLine(""))
                                .andThen {
                                    Kyo.foreach(Chunk.from(sizes)) { size =>
                                        benchSize(size, allJars, kyoBenchClassesDir, warmupIter, measureIter)
                                    }
                                }
                                .map { rows =>
                                    Console.printLine("=== Summary ===")
                                        .andThen(Console.printLine(
                                            f"${"size"}%-8s ${"jars"}%5s | ${"kyo-tasty med"}%14s ${"p95"}%9s | ${"tasty-query med"}%16s ${"p95"}%9s | ${"ratio (kt/tq)"}%14s | tq-classes tq-fail"
                                        ))
                                        .andThen {
                                            Kyo.foreach(rows) { r =>
                                                val ratio = r.kyoStats.median / r.tqStats.median
                                                Console.printLine(
                                                    f"${r.size}%-8s ${r.jarCount}%5d | ${r.kyoStats.median}%11.2f ms ${r.kyoStats.p95}%6.2f ms | ${r.tqStats.median}%13.2f ms ${r.tqStats.p95}%6.2f ms | ${ratio}%13.2fx | ${r.tqTopLevel}%10d ${r.tqFailures}%7d"
                                                )
                                            }
                                        }
                                        .andThen(Console.printLine("=== done ==="))
                                }
                        }
                    }
                }
            end if
        }
    }

end TastyQueryCompareBench
