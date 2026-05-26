package kyo.bench

import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarFile
import kyo.*
import kyo.internal.reflect.query.Classpath as InternalClasspath
import kyo.internal.reflect.query.ClasspathOrchestrator
import kyo.internal.reflect.query.ClasspathTestHelpers
import kyo.internal.reflect.query.FileSource
import kyo.internal.reflect.query.JvmFileSource
import kyo.internal.reflect.snapshot.DigestComputer
import kyo.internal.reflect.snapshot.SnapshotReader
import kyo.internal.reflect.snapshot.SnapshotWriter
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** Benchmark harness for kyo-reflect (DESIGN.md Section 20, G18).
  *
  * Runs six workloads from DESIGN.md Section 20 against the fixture TASTy files. Each workload is measured with System.nanoTime, 5 warm-up
  * iterations then 10 measurement iterations. Median and p95 are printed per workload.
  *
  * Fixture TASTy files are discovered at runtime by scanning all classpath entries from the JVM classloader for entries under kyo/fixtures
  * directory (suffix .tasty). This avoids any dependency on test-scope embedded byte arrays.
  *
  * Run with: sbt 'kyo-reflect-bench/run'
  */
object ReflectBench:

    private val benchDigest: Array[Byte] = Array[Byte](1, 2, 3, 4, 5, 6, 7, 8)
    private val cacheDir                 = "benchcache"

    /** Discover all .tasty resources under kyo/fixtures from the runtime classpath and load them into a MemoryFileSource.
      *
      * Iterates over URL classpath entries from the current classloader. For directory entries, finds .tasty files under kyo/fixtures/. The
      * virtual path in the source is "fixtures/name.tasty".
      */
    private def buildFixtureSource(): MemoryFileSource =
        val src = new MemoryFileSource()
        val cl  = Thread.currentThread().getContextClassLoader
        // Walk all URL entries in the classloader hierarchy.
        // Java 11+ AppClassLoader is not a URLClassLoader. Fall back to java.class.path property.
        val classpathEntries: Seq[java.nio.file.Path] = cl match
            case ucl: URLClassLoader =>
                ucl.getURLs.toSeq.flatMap: url =>
                    try Seq(Paths.get(url.toURI))
                    catch case _: Throwable => Seq.empty
            case _ =>
                java.lang.System.getProperty("java.class.path", "").split(java.io.File.pathSeparator).toSeq
                    .map(p => Paths.get(p))
        for entry <- classpathEntries do
            try
                if Files.isDirectory(entry) then
                    val fixtureDir = entry.resolve("kyo").resolve("fixtures")
                    if Files.isDirectory(fixtureDir) then
                        Files.list(fixtureDir).iterator().asScala
                            .filter(p => p.getFileName.toString.endsWith(".tasty") && Files.isRegularFile(p))
                            .foreach: p =>
                                val name  = p.getFileName.toString
                                val bytes = Files.readAllBytes(p)
                                src.add(s"fixtures/$name", bytes)
                    end if
                else if entry.toString.endsWith(".jar") && Files.isRegularFile(entry) then
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
    end buildFixtureSource

    /** In-memory FileSource for benchmarks. */
    final class MemoryFileSource(
        val files: mutable.HashMap[String, Array[Byte]] = mutable.HashMap.empty
    ) extends FileSource:

        def add(path: String, bytes: Array[Byte]): Unit = files(path) = bytes

        def list(dir: String, suffix: String)(using Frame): Chunk[String] < (Sync & Abort[ReflectError]) =
            Sync.defer:
                Chunk.from(files.keys.filter(k => k.startsWith(dir + "/") && k.endsWith(suffix)).toSeq)

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[ReflectError]) =
            files.get(path) match
                case Some(bytes) => bytes
                case None        => Abort.fail(ReflectError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Sync.defer(files(path) = bytes)

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            files.get(from) match
                case Some(bytes) =>
                    Sync.defer:
                        val _ = files.remove(from)
                        files(to) = bytes
                case None =>
                    Abort.fail(ReflectError.SnapshotIoError(s"rename: $from not found"))

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[ReflectError]) =
            Kyo.unit

        def exists(path: String)(using Frame): Boolean < Sync =
            Sync.defer(files.contains(path) || files.keys.exists(_.startsWith(path + "/")))

        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[ReflectError]) =
            Sync.defer:
                files.get(path) match
                    case Some(bytes) => FileSource.FileStat(mtimeMs = 0L, size = bytes.length.toLong)
                    case None        => Abort.fail(ReflectError.FileNotFound(path))

    end MemoryFileSource

    /** Open a classpath from an in-memory source. */
    private def openClasspath(
        src: FileSource
    )(using Frame): Reflect.Classpath < (Sync & Async & Scope & Abort[ReflectError]) =
        InternalClasspath.allocate.flatMap: rawCp =>
            Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                ClasspathOrchestrator.openInto(Seq("fixtures"), strict = false, src, concurrency = 1, rawCp).map: _ =>
                    val cp = Reflect.Classpath.wrap(rawCp)
                    ClasspathTestHelpers.assignHomesForTest(rawCp)
                    cp

    /** Open a classpath that the caller is responsible for closing via InternalClasspath.close. No Scope finalizer registered. */
    private def openClasspathManual(
        src: FileSource
    )(using Frame): (Reflect.Classpath, InternalClasspath) < (Sync & Async & Abort[ReflectError]) =
        InternalClasspath.allocate.flatMap: rawCp =>
            ClasspathOrchestrator.openInto(Seq("fixtures"), strict = false, src, concurrency = 1, rawCp).map: _ =>
                val cp = Reflect.Classpath.wrap(rawCp)
                ClasspathTestHelpers.assignHomesForTest(rawCp)
                (cp, rawCp)

    /** Write a snapshot and return the snapshot path. */
    private def writeSnapshot(
        src: FileSource,
        cacheSrc: MemoryFileSource
    )(using Frame): String < (Sync & Async & Scope & Abort[ReflectError]) =
        openClasspath(src).flatMap: cp =>
            SnapshotWriter.write(Reflect.Classpath.unwrap(cp), cacheDir, benchDigest, cacheSrc).map: _ =>
                s"$cacheDir/${DigestComputer.toHexString(benchDigest)}.krfl"

    /** Run an effect from the bench main, blocking until completion. Panics on failure. */
    private def runSync[A](v: => A < (Async & Scope & Abort[ReflectError]))(using AllowUnsafe, Frame): A =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(Abort.run[ReflectError](v).map {
            case Result.Success(a)   => a
            case Result.Failure(err) => throw new RuntimeException(s"Bench failed: $err")
            case Result.Panic(ex)    => throw ex
        }) match
            case Result.Success(a)   => a
            case Result.Failure(err) => throw err
            case Result.Panic(ex)    => throw ex

    /** Time one call to `action` in nanoseconds. */
    private def timeNs(action: => Unit): Long =
        val t0 = java.lang.System.nanoTime()
        action
        java.lang.System.nanoTime() - t0
    end timeNs

    /** Run `action` for `warmup` iterations (unmeasured) then `measure` iterations (timed). Print median and p95. */
    private def bench(name: String, warmup: Int, measure: Int)(action: => Unit): Unit =
        for _ <- 1 to warmup do action
        val times = new Array[Long](measure)
        for i <- 0 until measure do times(i) = timeNs(action)
        java.util.Arrays.sort(times)
        val median = times(measure / 2)
        val p95    = times((measure * 95 / 100).min(measure - 1))
        java.lang.System.out.println(
            f"[$name] median=${median / 1_000_000.0}%.2f ms  p95=${p95 / 1_000_000.0}%.2f ms"
        )
    end bench

    /** Recursively count the number of tree nodes that reference `targetName` via Ident, Select, or Apply. */
    private def countTreeRefs(tree: Reflect.Tree, targetName: String): Int =
        tree match
            case Reflect.Tree.Ident(name, _) =>
                if name.asString == targetName then 1 else 0
            case Reflect.Tree.Select(qual, name, _) =>
                (if name.asString == targetName then 1 else 0) + countTreeRefs(qual, targetName)
            case Reflect.Tree.Apply(fun, args) =>
                countTreeRefs(fun, targetName) + args.map(countTreeRefs(_, targetName)).sum
            case Reflect.Tree.TypeApply(fun, _) =>
                countTreeRefs(fun, targetName)
            case Reflect.Tree.Block(stats, expr) =>
                stats.map(countTreeRefs(_, targetName)).sum + countTreeRefs(expr, targetName)
            case Reflect.Tree.If(cond, thenp, elsep) =>
                countTreeRefs(cond, targetName) + countTreeRefs(thenp, targetName) + countTreeRefs(elsep, targetName)
            case Reflect.Tree.Match(selector, cases) =>
                countTreeRefs(selector, targetName) + cases.map: c =>
                    countTreeRefs(c.pattern, targetName) +
                        c.guard.map(countTreeRefs(_, targetName)).getOrElse(0) +
                        countTreeRefs(c.body, targetName)
                .sum
            case Reflect.Tree.Assign(lhs, rhs) =>
                countTreeRefs(lhs, targetName) + countTreeRefs(rhs, targetName)
            case Reflect.Tree.Typed(expr, _) =>
                countTreeRefs(expr, targetName)
            case Reflect.Tree.Inlined(call, bindings, body) =>
                call.map(countTreeRefs(_, targetName)).getOrElse(0) +
                    bindings.map(countTreeRefs(_, targetName)).sum +
                    countTreeRefs(body, targetName)
            case Reflect.Tree.Lambda(method, _) =>
                countTreeRefs(method, targetName)
            case Reflect.Tree.Try(expr, cases, finalizer) =>
                countTreeRefs(expr, targetName) +
                    cases.map(c => countTreeRefs(c.body, targetName)).sum +
                    finalizer.map(countTreeRefs(_, targetName)).getOrElse(0)
            case Reflect.Tree.While(cond, body) =>
                countTreeRefs(cond, targetName) + countTreeRefs(body, targetName)
            case Reflect.Tree.Throw(expr) =>
                countTreeRefs(expr, targetName)
            case Reflect.Tree.Return(expr, _) =>
                expr.map(countTreeRefs(_, targetName)).getOrElse(0)
            case Reflect.Tree.ValDef(_, _, rhs) =>
                rhs.map(countTreeRefs(_, targetName)).getOrElse(0)
            case Reflect.Tree.DefDef(_, paramss, _, rhs) =>
                paramss.flatMap(_.map(countTreeRefs(_, targetName))).sum +
                    rhs.map(countTreeRefs(_, targetName)).getOrElse(0)
            case Reflect.Tree.ClassDef(_, template) =>
                template.body.map(countTreeRefs(_, targetName)).sum
            case Reflect.Tree.PackageDef(_, stats) =>
                stats.map(countTreeRefs(_, targetName)).sum
            case _ =>
                0
    end countTreeRefs

    def main(args: Array[String]): Unit =
        import AllowUnsafe.embrace.danger

        val warmupIter  = 5
        val measureIter = 10

        java.lang.System.out.println("=== kyo-reflect benchmark harness (DESIGN.md Section 20) ===")
        java.lang.System.out.println()

        val fixtureSrc = buildFixtureSource()
        java.lang.System.out.println(s"Loaded ${fixtureSrc.files.size} fixture TASTy files from classpath.")
        if fixtureSrc.files.isEmpty then
            val cp = java.lang.System.getProperty("java.class.path", "")
            java.lang.System.err.println(
                s"ERROR: no fixture TASTy files found on classpath. CP entries: ${cp.split(java.io.File.pathSeparator).filter(_.contains("reflect")).mkString(", ")}"
            )
            java.lang.System.exit(1)
        end if
        java.lang.System.out.println()

        // Workload 1: cold-load fixture files, enumerate all top-level classes.
        bench("W1 cold-load enumerate top-level classes", warmupIter, measureIter):
            val _ = runSync:
                Scope.run:
                    openClasspath(fixtureSrc).map: cp =>
                        cp.topLevelClasses.size

        java.lang.System.out.println()

        // Workload 2: cold-load with snapshot cache miss (write snapshot on each run).
        bench("W2 cold-load snapshot cache miss + write", warmupIter, measureIter):
            val _ = runSync:
                val cacheSrc = new MemoryFileSource()
                Scope.run:
                    writeSnapshot(fixtureSrc, cacheSrc).map(_.length)

        java.lang.System.out.println()

        // Workload 3: warm-load with snapshot cache hit (snapshot pre-written; only reload is timed).
        val snapshotCacheSrc = new MemoryFileSource()
        val snapshotPath: String =
            runSync:
                Scope.run:
                    writeSnapshot(fixtureSrc, snapshotCacheSrc)

        bench("W3 warm-load snapshot cache hit (heap read)", warmupIter, measureIter):
            val _ = runSync:
                Scope.run:
                    InternalClasspath.allocate.flatMap: rawCp =>
                        Scope.ensure(Sync.defer(InternalClasspath.close(rawCp))).andThen:
                            SnapshotReader.read(snapshotPath, snapshotCacheSrc, rawCp).flatMap: _ =>
                                rawCp.allTopLevelClasses.map(_.size)

        java.lang.System.out.println()

        // Workload 4: per-FQN lookup of fixture class names (warm cache).
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

        // Open a warm classpath that stays live across W4, W5, W8. Caller closes it manually after benchmarks.
        val (warmCp, warmRawCp): (Reflect.Classpath, InternalClasspath) =
            runSync:
                Scope.run:
                    openClasspathManual(fixtureSrc)

        try
            bench("W4 per-FQN lookup warm cache", warmupIter, measureIter):
                var hits = 0
                for fqn <- fqnsToLookup do
                    warmCp.findClass(fqn) match
                        case Present(_) => hits += 1
                        case Absent     => ()
                end for

            java.lang.System.out.println(s"  (${fqnsToLookup.size} lookups per run)")
            java.lang.System.out.println()

            // Workload 5: declarations enumeration on a class with declared members.
            bench("W5 declarations enumeration (PlainClass)", warmupIter, measureIter):
                val count = warmCp.findClass("kyo.fixtures.PlainClass") match
                    case Present(sym) => sym.declarations.size
                    case Absent       => 0
                val _ = count

            java.lang.System.out.println()

            // Workload 8: plain iteration over all top-level classes and their declarations.
            bench("W8 plain iteration (pure accessor for-comp)", warmupIter, measureIter):
                val tops  = warmCp.topLevelClasses
                var total = 0
                for cls <- tops do
                    val decls = cls.declarations
                    total += decls.count(_.kind == Reflect.SymbolKind.Method)
                    if cls.kind == Reflect.SymbolKind.Method then total += 1
                end for

            java.lang.System.out.println(s"  (pure for-comp over Symbol accessors, no effect threading)")
            java.lang.System.out.println()

            // Workload 9: hover-shaped query (pure, sub-ms target).
            // Walk topLevelClasses, find the first Method symbol, return name + scaladoc + kind string.
            bench("W9 hover-shaped query (pure accessors)", warmupIter, measureIter):
                val tops   = warmCp.topLevelClasses
                var result = ""
                var found  = false
                for cls <- tops if !found do
                    for sym <- cls.declarations if !found do
                        if sym.kind == Reflect.SymbolKind.Method then
                            val sig  = sym.name.asString
                            val doc  = sym.scaladoc.getOrElse("")
                            val kind = sym.kind.toString
                            result = s"$sig $doc $kind"
                            found = true
                end for
                val _ = result

            java.lang.System.out.println(s"  (pure walk: topLevelClasses -> declarations -> name/scaladoc/kind)")
            java.lang.System.out.println()

            // Workload 10: find-references-shaped query.
            // For target name "apply", walk all Method symbols, decode each body, count tree nodes that
            // reference that name via Apply/Select/Ident. Uses Kyo.foreach to iterate body decodes.
            bench("W10 find-references-shaped (body decode + tree walk)", warmupIter, measureIter):
                val targetName = "apply"
                val total = runSync:
                    val tops    = warmCp.topLevelClasses
                    val methods = tops.flatMap(_.declarations.filter(_.kind == Reflect.SymbolKind.Method))
                    val counts: Chunk[Int] < Sync =
                        Kyo.foreach(methods): (sym: Reflect.Symbol) =>
                            Abort.run[ReflectError](sym.body.map((tree: Reflect.Tree) => countTreeRefs(tree, targetName)))
                                .map:
                                    case Result.Success(n) => n
                                    case _                 => 0
                    counts.map(_.foldLeft(0)(_ + _))
                val _ = total

            java.lang.System.out.println(s"  (body decode + recursive Tree walk for name references)")
            java.lang.System.out.println()
            java.lang.System.out.println("=== done ===")
        finally
            InternalClasspath.close(warmRawCp)
        end try
    end main

end ReflectBench
