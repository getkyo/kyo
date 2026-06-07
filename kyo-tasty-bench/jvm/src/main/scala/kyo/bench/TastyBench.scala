package kyo.bench

import java.net.URLClassLoader
import java.util.jar.JarFile
import kyo.*
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import kyo.internal.tasty.symbol.SymbolKind
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Benchmark harness for kyo-tasty.
  *
  * Runs nine workloads against the fixture TASTy files. Each workload is measured with
  * Clock.nowMonotonic, 5 warm-up iterations then 10 measurement iterations. Median and p95 are
  * printed per workload.
  *
  * Fixture TASTy files are discovered at runtime by scanning all classpath entries from the JVM
  * classloader for entries under kyo/fixtures directory (suffix .tasty). This avoids any
  * dependency on test-scope embedded byte arrays.
  *
  * Run with: sbt 'kyo-tasty-bench/run'
  */
object TastyBench extends KyoApp:

    private val benchDigest: Array[Byte] = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
    private val cacheDir                 = "benchcache"

    /** Discover all .tasty resources under kyo/fixtures from the runtime classpath and load them
      * into a MemoryFileSource.
      *
      * Iterates over URL classpath entries from the current classloader. For directory entries,
      * finds .tasty files under kyo/fixtures/. The virtual path in the source is
      * "fixtures/name.tasty".
      */
    private def buildFixtureSource(): MemoryFileSource < Sync =
        System.property[String]("java.class.path", "").map { cp =>
            Sync.defer {
                val src = new MemoryFileSource()
                val cl  = Thread.currentThread().getContextClassLoader
                val classpathEntries: Seq[java.nio.file.Path] = cl match
                    case ucl: URLClassLoader =>
                        ucl.getURLs.toSeq.flatMap { url =>
                            try Seq(java.nio.file.Paths.get(url.toURI))
                            catch case _: Throwable => Seq.empty
                        }
                    case _ =>
                        cp.split(Path.pathSeparator).toSeq.map(p => java.nio.file.Paths.get(p))
                for entry <- classpathEntries do
                    try
                        if java.nio.file.Files.isDirectory(entry) then
                            val fixtureDir = entry.resolve("kyo").resolve("fixtures")
                            if java.nio.file.Files.isDirectory(fixtureDir) then
                                java.nio.file.Files.list(fixtureDir).iterator().asScala
                                    .filter(p =>
                                        p.getFileName.toString.endsWith(".tasty") &&
                                            java.nio.file.Files.isRegularFile(p)
                                    )
                                    .foreach { p =>
                                        val name  = p.getFileName.toString
                                        val bytes = java.nio.file.Files.readAllBytes(p)
                                        src.add(s"fixtures/$name", bytes)
                                    }
                            end if
                        else if entry.toString.endsWith(".jar") && java.nio.file.Files.isRegularFile(entry) then
                            val jar = new JarFile(entry.toFile)
                            try
                                val entries = jar.entries()
                                while entries.hasMoreElements do
                                    val je     = entries.nextElement()
                                    val jeName = je.getName
                                    if jeName.startsWith("kyo/fixtures/") && jeName.endsWith(".tasty") && !je.isDirectory then
                                        val name = jeName.stripPrefix("kyo/fixtures/")
                                        val in   = jar.getInputStream(je)
                                        val bytes =
                                            try in.readAllBytes()
                                            finally in.close()
                                        src.add(s"fixtures/$name", bytes)
                                    end if
                                end while
                            finally jar.close()
                            end try
                        end if
                    catch
                        case _: Throwable => ()
                end for
                src
            }
        }

    /** In-memory FileSource for benchmarks. */
    final class MemoryFileSource(
        val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty
    ) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && suffixes.exists(k.endsWith)).toSeq)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        val _ = files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(TastyError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(TastyError.FileNotFound(path))

    end MemoryFileSource

    /** Open a classpath from an in-memory source. */
    private def openClasspath(
        src: FileSource
    )(using Frame): Tasty.Classpath < (Sync & Async & Scope & Abort[TastyError]) =
        ClasspathOrchestrator.init(Seq("fixtures"), Tasty.ErrorMode.SoftFail, src, concurrency = 1)

    /** Write a snapshot and return the snapshot path. */
    private def writeSnapshot(
        src: FileSource,
        cacheSrc: MemoryFileSource
    )(using Frame): String < (Sync & Async & Scope & Abort[TastyError]) =
        openClasspath(src).map { cp =>
            SnapshotWriter.write(cp, cacheDir, benchDigest, cacheSrc).map { _ =>
                s"$cacheDir/${DigestComputer.toHexString(benchDigest)}.krfl"
            }
        }

    /** Run an effect from the bench main, blocking until completion. Panics on failure. */
    private def runSync[A](v: => A < (Sync & Async & Scope & Abort[TastyError]))(using AllowUnsafe, Frame): A =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Abort.run[TastyError](v).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new RuntimeException(s"Bench failed: $err")
            case Result.Panic(ex)    => throw ex
        }) match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw err
            case Result.Panic(ex)    => throw ex

    /** Time one call to action and return the elapsed Duration. */
    private def timed(action: => Unit < Sync)(using Frame): Duration < Sync =
        Clock.nowMonotonic.map { start =>
            action.map { _ =>
                Clock.nowMonotonic.map { end =>
                    end - start
                }
            }
        }

    /** Run action for warmup iterations (unmeasured) then measure iterations (timed). Print
      * median and p95. Returns the sorted durations.
      */
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

    /** Recursively count the number of tree nodes that reference targetName via Ident, Select, or
      * Apply.
      */
    private def countTreeRefs(tree: Tasty.Tree, targetName: String): Int =
        import Tasty.Name.asString
        tree match
            case Tasty.Tree.Ident(name, _) =>
                if name.asString == targetName then 1 else 0
            case Tasty.Tree.Select(qual, name, _) =>
                (if name.asString == targetName then 1 else 0) + countTreeRefs(qual, targetName)
            case Tasty.Tree.Apply(fun, args) =>
                countTreeRefs(fun, targetName) + args.map(countTreeRefs(_, targetName)).sum
            case Tasty.Tree.TypeApply(fun, _) =>
                countTreeRefs(fun, targetName)
            case Tasty.Tree.Block(stats, expr) =>
                stats.map(countTreeRefs(_, targetName)).sum + countTreeRefs(expr, targetName)
            case Tasty.Tree.If(cond, thenp, elsep) =>
                countTreeRefs(cond, targetName) + countTreeRefs(thenp, targetName) +
                    countTreeRefs(elsep, targetName)
            case _ =>
                0
        end match
    end countTreeRefs

    run {
        import AllowUnsafe.embrace.danger

        val warmupIter  = 5
        val measureIter = 10

        Console.printLine("=== kyo-tasty benchmark harness ===")
            .andThen(Console.printLine(""))
            .andThen(buildFixtureSource())
            .map { fixtureSrc =>
                Console.printLine(s"Loaded ${fixtureSrc.files.size} fixture TASTy files from classpath.")
                    .andThen {
                        if fixtureSrc.files.isEmpty then
                            System.property[String]("java.class.path", "").map { cp =>
                                Console.printLineErr(
                                    s"ERROR: no fixture TASTy files found on classpath. CP entries: ${cp.split(Path.pathSeparator).filter(_.contains("reflect")).mkString(", ")}"
                                ).andThen(Sync.defer(exit(1)))
                            }
                        else
                            Console.printLine("")
                                .andThen {
                                    // Workload 1: cold-load fixture files, enumerate all top-level classes.
                                    bench("cold-load enumerate top-level classes", warmupIter, measureIter)(Sync.defer {
                                        val _ = runSync {
                                            Scope.run {
                                                openClasspath(fixtureSrc).map(_.topLevelClasses.size)
                                            }
                                        }
                                    })
                                }
                                .andThen(Console.printLine(""))
                                .andThen {
                                    // Workload 2: cold-load with snapshot cache miss (write snapshot on each run).
                                    bench("cold-load snapshot cache miss + write", warmupIter, measureIter)(Sync.defer {
                                        val _ = runSync {
                                            val cacheSrc = new MemoryFileSource()
                                            Scope.run {
                                                writeSnapshot(fixtureSrc, cacheSrc).map(_.length)
                                            }
                                        }
                                    })
                                }
                                .andThen(Console.printLine(""))
                                .andThen {
                                    // Workload 3: warm-load with snapshot cache hit (snapshot pre-written; only reload is timed).
                                    Sync.defer {
                                        val snapshotCacheSrc = new MemoryFileSource()
                                        val snapshotPath: String =
                                            runSync {
                                                Scope.run {
                                                    writeSnapshot(fixtureSrc, snapshotCacheSrc)
                                                }
                                            }
                                        (snapshotCacheSrc, snapshotPath)
                                    }.map { case (snapshotCacheSrc, snapshotPath) =>
                                        bench("warm-load snapshot cache hit (heap read)", warmupIter, measureIter)(Sync.defer {
                                            val _ = runSync {
                                                Scope.run {
                                                    SnapshotReader.read(snapshotPath, snapshotCacheSrc).map(_.topLevelClasses.size)
                                                }
                                            }
                                        })
                                    }
                                }
                                .andThen(Console.printLine(""))
                                .andThen {
                                    // Workloads 4, 5, 8, 9, 10: all use a shared warm classpath.
                                    val fqnsToLookup = Seq(
                                        "kyo.fixtures.PlainClass",
                                        "kyo.fixtures.SomeObject",
                                        "kyo.fixtures.SomeTrait",
                                        "kyo.fixtures.SomeCaseClass",
                                        "kyo.fixtures.GenericBox",
                                        "kyo.fixtures.Color",
                                        "kyo.fixtures.Outer",
                                        "kyo.fixtures.ChildClass",
                                        "kyo.fixtures.BaseClass"
                                    )

                                    // Open a warm classpath that stays live across the remaining workloads.
                                    Sync.defer {
                                        val cp: Tasty.Classpath = runSync {
                                            openClasspath(fixtureSrc)
                                        }
                                        cp
                                    }.map { warmCp =>
                                        given Tasty.Classpath = warmCp

                                        // Workload 4: per-FQN lookup.
                                        bench("per-FQN lookup warm cache", warmupIter, measureIter)(Sync.defer {
                                            var hits = 0
                                            for fqn <- fqnsToLookup do
                                                warmCp.findClass(fqn) match
                                                    case Present(_) => hits += 1
                                                    case Absent     => ()
                                            end for
                                        })
                                            .andThen(Console.printLine(s"  (${fqnsToLookup.size} lookups per run)"))
                                            .andThen(Console.printLine(""))
                                            .andThen {
                                                // Workload 5: declarations enumeration.
                                                bench("declarations enumeration (PlainClass)", warmupIter, measureIter)(Sync.defer {
                                                    val count = warmCp.findClass("kyo.fixtures.PlainClass") match
                                                        case Present(sym) =>
                                                            sym.declarationIds.flatMap(id => warmCp.symbol(id).toChunk).size
                                                        case Absent => 0
                                                    val _ = count
                                                })
                                            }
                                            .andThen(Console.printLine(""))
                                            .andThen {
                                                // Workload 8: plain iteration.
                                                bench("plain iteration (pure accessor for-comp)", warmupIter, measureIter)(Sync.defer {
                                                    val tops  = warmCp.topLevelClasses
                                                    var total = 0
                                                    for cls <- tops do
                                                        val decls = cls.declarationIds.flatMap(id => warmCp.symbol(id).toChunk)
                                                        total += decls.count(_.kind == SymbolKind.Method)
                                                        if cls.kind == SymbolKind.Method then total += 1
                                                    end for
                                                })
                                            }
                                            .andThen(Console.printLine(s"  (pure for-comp over Symbol accessors, no effect threading)"))
                                            .andThen(Console.printLine(""))
                                            .andThen {
                                                // Workload 9: hover-shaped query.
                                                bench("hover-shaped query (pure accessors)", warmupIter, measureIter)(Sync.defer {
                                                    val tops   = warmCp.topLevelClasses
                                                    var result = ""
                                                    var found  = false
                                                    for cls <- tops if !found do
                                                        for sym <- cls.declarationIds.flatMap(id => warmCp.symbol(id).toChunk) if !found do
                                                            if sym.kind == SymbolKind.Method then
                                                                val sig  = sym.name.asString
                                                                val doc  = sym.scaladoc.getOrElse("")
                                                                val kind = sym.kind.toString
                                                                result = s"$sig $doc $kind"
                                                                found = true
                                                        end for
                                                    end for
                                                    val _ = result
                                                })
                                            }
                                            .andThen(Console.printLine(
                                                s"  (pure walk: topLevelClasses -> declarations -> name/scaladoc/kind)"
                                            ))
                                            .andThen(Console.printLine(""))
                                            .andThen {
                                                // Workload 10: find-references-shaped query.
                                                bench(
                                                    "find-references-shaped (body decode + tree walk)",
                                                    warmupIter,
                                                    measureIter
                                                )(Sync.defer {
                                                    val targetName = "apply"
                                                    val total = runSync {
                                                        Tasty.withClasspath(warmCp) {
                                                            val tops = warmCp.topLevelClasses
                                                            val methods: Chunk[Tasty.Symbol.Method] =
                                                                tops.flatMap(_.declarationIds.flatMap(id =>
                                                                    warmCp.symbol(id).toChunk
                                                                )).collect {
                                                                    case m: Tasty.Symbol.Method => m
                                                                }
                                                            val counts: Chunk[Int] < (Sync & Abort[TastyError]) =
                                                                Kyo.foreach(methods) { (m: Tasty.Symbol.Method) =>
                                                                    Tasty.bodyTree(m).map {
                                                                        case Maybe.Present(tree) => countTreeRefs(tree, targetName)
                                                                        case Maybe.Absent        => 0
                                                                    }
                                                                }
                                                            counts.map(_.foldLeft(0)(_ + _))
                                                        }
                                                    }
                                                    val _ = total
                                                })
                                            }
                                            .andThen(Console.printLine(s"  (body decode + recursive Tree walk for name references)"))
                                            .andThen(Console.printLine(""))
                                            .andThen(Console.printLine("=== done ==="))
                                    }
                                }
                        end if
                    }
            }
    }

end TastyBench
