package kyo.bench

import kyo.*
import scala.jdk.CollectionConverters.*

/** Cold-load profiling harness for the FULL kyo-bench classpath (121 jars + kyo-bench classes
  * directory).
  *
  * Reads the classpath from /tmp/kyo-bench-cp.txt (produced by: sbt 'show
  * kyo-bench/fullClasspath' 2>&1 | grep -oE '/[^ ]*\.jar' | sort -u > /tmp/kyo-bench-cp.txt )
  * and adds the kyo-bench compiled-classes directory as an additional root.
  *
  * The kyo-bench classes directory is resolved in order:
  *   1. The KYO_BENCH_CLASSES_DIR environment variable (set to the absolute path of the
  *      kyo-bench JVM classes directory).
  *   2. A CWD-relative fallback: kyo-bench/.jvm/target/scala-3.8.3/classes
  *
  * Runs 3 warmup + 5 measurement iterations (cold load at this scale is 1-5s each).
  *
  * Run baseline: sbt 'kyo-tasty-bench/runMain kyo.bench.ColdLoadFullBench'
  *
  * Run with CPU profiling:
  * sbt -J-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/full-flame.html
  * 'kyo-tasty-bench/runMain kyo.bench.ColdLoadFullBench'
  *
  * Run with allocation profiling:
  * sbt -J-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=alloc,file=/tmp/full-alloc.html
  * 'kyo-tasty-bench/runMain kyo.bench.ColdLoadFullBench'
  */
object ColdLoadFullBench extends KyoApp:

    private val cpFile = "/tmp/kyo-bench-cp.txt"

    /** Time one call to action and return the elapsed Duration. */
    private def timed(action: => Unit < Sync)(using Frame): Duration < Sync =
        Clock.nowMonotonic.map { start =>
            action.map { _ =>
                Clock.nowMonotonic.map { end =>
                    end - start
                }
            }
        }

    private def bench(name: String, warmup: Int, measure: Int)(action: => Unit < Sync)(using Frame): Chunk[Duration] < Sync =
        Console.printLine(s"  warmup ($warmup iters)...").map { _ =>
            Kyo.foreach(Chunk.from(0 until warmup)) { i =>
                Console.print(s"    warmup ${i + 1}/$warmup... ").map { _ =>
                    timed(action).map { d =>
                        Console.printLine(f"${d.toNanos / 1_000_000.0}%.0f ms").map { _ =>
                            d
                        }
                    }
                }
            }.map { _ =>
                Console.printLine(s"  measuring ($measure iters)...").map { _ =>
                    Kyo.foreach(Chunk.from(0 until measure)) { i =>
                        Console.print(s"    iter ${i + 1}/$measure... ").map { _ =>
                            timed(action).map { d =>
                                Console.printLine(f"${d.toNanos / 1_000_000.0}%.0f ms").map { _ =>
                                    d
                                }
                            }
                        }
                    }.map { durations =>
                        val sorted = durations.sortBy(_.toNanos)
                        val median = sorted(measure / 2)
                        val p95    = sorted((measure * 95 / 100).min(measure - 1))
                        Console.printLine(
                            f"[$name] median=${median.toNanos / 1_000_000.0}%.2f ms  p95=${p95.toNanos / 1_000_000.0}%.2f ms"
                        ).map { _ =>
                            sorted
                        }
                    }
                }
            }
        }

    private def runSync[A](v: => A < (Async & Abort[TastyError]))(using AllowUnsafe, Frame): A =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Abort.run[TastyError](v).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new RuntimeException(s"ColdLoadFullBench failed: $err")
            case Result.Panic(ex)    => throw ex
        }) match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw err
            case Result.Panic(ex)    => throw ex

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

    run {
        import AllowUnsafe.embrace.danger

        val warmupIter  = args.lift(0).flatMap(_.toIntOption).getOrElse(5)
        val measureIter = args.lift(1).flatMap(_.toIntOption).getOrElse(10)

        Path(cpFile).isRegularFile.map { cpExists =>
            if !cpExists then
                Console.printLineErr(s"ERROR: classpath file not found: $cpFile")
                    .andThen(Console.printLineErr(
                        "Run: sbt 'show kyo-bench/fullClasspath' 2>&1 | grep -oE '/[^ ]*\\.jar' | sort -u > /tmp/kyo-bench-cp.txt"
                    ))
                    .andThen(Sync.defer(exit(1)))
            else
                // Load jar paths from /tmp/kyo-bench-cp.txt.
                Path(cpFile).readLines.map { rawLines =>
                    val candidates = rawLines.filter(_.trim.nonEmpty).map(_.trim)
                    Kyo.foreach(candidates) { trimmed =>
                        Path(trimmed).isRegularFile.map { exists =>
                            if exists then Present(trimmed) else Absent
                        }
                    }.map { maybes =>
                        val jarPaths = maybes.flatMap(_.toChunk)
                        resolveClassesDir().map { classesDir =>
                            Path(classesDir).isDirectory.map { classesDirExists =>
                                val allRoots: Seq[String] =
                                    val roots = scala.collection.mutable.ArrayBuffer.empty[String]
                                    roots ++= jarPaths.toSeq
                                    if classesDirExists then roots += classesDir
                                    roots.toSeq
                                end allRoots

                                // Count files in kyo-bench classes dir.
                                val classesDirWalkEff: (Long, Long, Long) < (Async & Abort[FileFsException]) =
                                    if classesDirExists then
                                        Scope.run(Path(classesDir).walk.run).map { paths =>
                                            val tasty    = paths.count(p => p.toString.endsWith(".tasty"))
                                            val clazz    = paths.count(p => p.toString.endsWith(".class"))
                                            val relevant = paths.filter(p => p.toString.endsWith(".tasty") || p.toString.endsWith(".class"))
                                            Kyo.foreach(relevant) { p =>
                                                Abort.run[FileReadException](p.size).map {
                                                    case Result.Success(s) => s
                                                    case _                 => 0L
                                                }
                                            }.map { sizes =>
                                                (tasty.toLong, clazz.toLong, sizes.foldLeft(0L)(_ + _))
                                            }
                                        }
                                    else
                                        (0L, 0L, 0L)

                                classesDirWalkEff.map { case (dirTasty, dirClass, dirBytes) =>
                                    // Count in jars (uncompressed sizes) - stays java.util.jar.JarFile (kyo gap, bench-only).
                                    Sync.defer {
                                        var tastyCount = dirTasty
                                        var classCount = dirClass
                                        var totalBytes = dirBytes
                                        for jar <- jarPaths.toSeq do
                                            try
                                                val jf = new java.util.jar.JarFile(jar)
                                                try
                                                    jf.entries().asIterator().asScala.foreach { e =>
                                                        if e.getName.endsWith(".tasty") then
                                                            tastyCount += 1
                                                            totalBytes += e.getSize
                                                        else if e.getName.endsWith(".class") then
                                                            classCount += 1
                                                            totalBytes += e.getSize
                                                    }
                                                finally jf.close()
                                                end try
                                            catch
                                                case _: Throwable => ()
                                        end for
                                        (tastyCount, classCount, totalBytes)
                                    }.map { case (tastyCount, classCount, totalBytes) =>
                                        val totalMB = totalBytes.toDouble / (1024 * 1024)

                                        Console.printLine("=== ColdLoadFullBench: FULL kyo-bench classpath ===")
                                            .andThen(Console.printLine(s"Jars: ${jarPaths.size}"))
                                            .andThen(Console.printLine(s"  + kyo-bench classes dir: $classesDir"))
                                            .andThen(Console.printLine(s"Total roots: ${allRoots.size}"))
                                            .andThen(Console.printLine(
                                                f"TASTy files: $tastyCount, class files: $classCount, uncompressed: $totalMB%.2f MB"
                                            ))
                                            .andThen(Console.printLine(""))
                                            .andThen {
                                                // Uninstrumented baseline.
                                                Console.printLine("=== full: cold-load full classpath (no snapshot) ===")
                                                    .andThen {
                                                        bench("full cold-load (full classpath)", warmupIter, measureIter)(Sync.defer {
                                                            val _ = runSync {
                                                                Tasty.withClasspath(allRoots) {
                                                                    Tasty.classpath.map(_.topLevelClasses.size)
                                                                }
                                                            }
                                                        })
                                                    }
                                            }
                                            .map { times =>
                                                Console.printLine("")
                                                    .andThen {
                                                        // Snapshot path.
                                                        Console.printLine(
                                                            "=== full+snapshot: cold-load full classpath + snapshot cache ==="
                                                        )
                                                            .andThen {
                                                                Path.tempDir("kyo-tasty-full-profile").map { tmpDir =>
                                                                    bench(
                                                                        "full+snapshot cold-load + snapshot (full classpath)",
                                                                        warmupIter,
                                                                        measureIter
                                                                    )(Sync.defer {
                                                                        val _ = runSync {
                                                                            Tasty.withClasspath(allRoots, Maybe.Present(tmpDir.toString)) {
                                                                                Tasty.classpath.map(_.topLevelClasses.size)
                                                                            }
                                                                        }
                                                                    }).map { snapshotTimes =>
                                                                        Console.printLine("")
                                                                            .andThen(Console.printLine("=== Summary ==="))
                                                                            .andThen(Console.printLine(
                                                                                f"full  median=${times(measureIter / 2).toNanos / 1_000_000.0}%.2f ms  p95=${times((measureIter * 95 / 100).min(measureIter - 1)).toNanos / 1_000_000.0}%.2f ms"
                                                                            ))
                                                                            .andThen(Console.printLine(
                                                                                f"full+snapshot median=${snapshotTimes(measureIter / 2).toNanos / 1_000_000.0}%.2f ms  p95=${snapshotTimes((measureIter * 95 / 100).min(measureIter - 1)).toNanos / 1_000_000.0}%.2f ms"
                                                                            ))
                                                                            .andThen(Console.printLine("=== done ==="))
                                                                            .andThen {
                                                                                // If OTLP export is active, sleep briefly before exit to allow
                                                                                // the background batch-flush fiber one full export cycle.
                                                                                // OTLPTraceExporter.shutdown is private[otlp] so the accessible
                                                                                // fallback is Thread.sleep. The default bspScheduleDelay is 5 s
                                                                                // so 6 s here is sufficient to capture the final batch.
                                                                                System.env[String]("OTEL_EXPORTER_OTLP_ENDPOINT").map {
                                                                                    case Present(_) =>
                                                                                        Console.printLine(
                                                                                            "[OTLP] waiting for background span flush..."
                                                                                        )
                                                                                            .andThen(Sync.defer(Thread.sleep(6000)))
                                                                                            .andThen(Console.printLine(
                                                                                                "[OTLP] flush wait complete"
                                                                                            ))
                                                                                    case Absent =>
                                                                                        Kyo.unit
                                                                                }
                                                                            }
                                                                    }
                                                                }
                                                            }
                                                    }
                                            }
                                    }
                                }
                            }
                        }
                    }
                }
            end if
        }
    }

end ColdLoadFullBench
