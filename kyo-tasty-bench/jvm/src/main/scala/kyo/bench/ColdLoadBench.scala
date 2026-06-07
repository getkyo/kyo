package kyo.bench

import kyo.*

/** Standalone cold-load profiling harness for kyo-tasty.
  *
  * Opens the kyo-bench compiled TASTy directory via Tasty.Classpath.open, runs 5 warmup + 10
  * measurement iterations, and prints median/p95 to stdout. Designed to be run with
  * async-profiler attached via -agentpath.
  *
  * The classes directory is resolved in order:
  *   1. The KYO_BENCH_CLASSES_DIR environment variable (set to the absolute path of the
  *      kyo-bench JVM classes directory).
  *   2. A CWD-relative fallback: kyo-bench/.jvm/target/scala-3.8.3/classes
  *
  * Run with: sbt -J-agentpath:/opt/homebrew/lib/libasyncProfiler.dylib=start,event=cpu,file=/tmp/cold-load-flame.html
  * 'kyo-tasty-bench/runMain kyo.bench.ColdLoadProfile'
  */
object ColdLoadProfile extends KyoApp:

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
        Kyo.foreachDiscard(Chunk.from(0 until warmup)) { _ =>
            action
        }.map { _ =>
            Kyo.foreach(Chunk.from(0 until measure)) { _ =>
                timed(action)
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

    private def runSync[A](v: => A < (Async & Abort[TastyError]))(using AllowUnsafe, Frame): A =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Abort.run[TastyError](v).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new RuntimeException(s"ColdLoadProfile failed: $err")
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

        val warmupIter  = 5
        val measureIter = args.headOption.flatMap(_.toIntOption).getOrElse(10)

        resolveClassesDir().map { root =>
            Path(root).isDirectory.map { rootExists =>
                if !rootExists then
                    Console.printLineErr(s"ERROR: kyo-bench classes directory not found: $root")
                        .andThen(Console.printLineErr("Run: sbt 'kyo-bench/compile' first"))
                        .andThen(Sync.defer(exit(1)))
                else
                    // Count TASTy and class files, total bytes.
                    Scope.run(Path(root).walk.run).map { allPaths =>
                        val tastyFiles = allPaths.count(p => p.toString.endsWith(".tasty"))
                        val classFiles = allPaths.count(p => p.toString.endsWith(".class"))
                        val relevantPaths =
                            allPaths.filter(p => p.toString.endsWith(".tasty") || p.toString.endsWith(".class"))
                        Kyo.foreach(relevantPaths) { p =>
                            Abort.run[FileReadException](p.size).map {
                                case Result.Success(s) => s
                                case _                 => 0L
                            }
                        }.map { sizes =>
                            val totalBytes = sizes.foldLeft(0L)(_ + _)
                            val totalMB    = totalBytes.toDouble / (1024 * 1024)

                            Console.printLine("=== ColdLoadProfile: kyo-bench cold-load ===")
                                .andThen(Console.printLine(s"Root: $root"))
                                .andThen(Console.printLine(f"Files: $tastyFiles TASTy + $classFiles classfiles, ${totalMB}%.2f MB"))
                                .andThen(Console.printLine(""))
                                .andThen {
                                    bench("cold-load kyo-bench (enumerate top-level classes)", warmupIter, measureIter)(Sync.defer {
                                        val _ = runSync {
                                            Tasty.withClasspath(Seq(root)) {
                                                Tasty.classpath.map(_.topLevelClasses.size)
                                            }
                                        }
                                    })
                                }
                                .map { times =>
                                    Console.printLine("")
                                        .andThen {
                                            // Also run snapshot-write timing.
                                            Path.tempDir("kyo-tasty-profile").map { tmpDir =>
                                                bench("cold-load kyo-bench + snapshot write", warmupIter, measureIter)(Sync.defer {
                                                    val _ = runSync {
                                                        Tasty.withClasspath(Seq(root), Maybe.Present(tmpDir.toString)) {
                                                            Tasty.classpath.map(_.topLevelClasses.size)
                                                        }
                                                    }
                                                }).map { snapshotTimes =>
                                                    Console.printLine("")
                                                        .andThen(Console.printLine("=== Summary ==="))
                                                        .andThen(Console.printLine(
                                                            f"cold-load           median=${times(measureIter / 2).toNanos / 1_000_000.0}%.2f ms  p95=${times((measureIter * 95 / 100).min(measureIter - 1)).toNanos / 1_000_000.0}%.2f ms"
                                                        ))
                                                        .andThen(Console.printLine(
                                                            f"cold-load+snapshot  median=${snapshotTimes(measureIter / 2).toNanos / 1_000_000.0}%.2f ms  p95=${snapshotTimes((measureIter * 95 / 100).min(measureIter - 1)).toNanos / 1_000_000.0}%.2f ms"
                                                        ))
                                                        .andThen(Console.printLine("=== done ==="))
                                                }
                                            }
                                        }
                                }
                        }
                    }
                end if
            }
        }
    }

end ColdLoadProfile
