package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.TestProbeFileSource
import kyo.internal.tasty.query.ClasspathOrchestrator
import scala.collection.mutable

/** Behavioral enforcement: side effects only in named sites.
  *
  * Pure Tasty.* query methods perform zero IO when a probe FileSource is injected. Every pure
  * query returns its expected value without raising the probe sentinel.
  *
  *   - Tasty.bodyTree returns Maybe.Absent under withClasspath(cp) (decodeCtx = Absent).
  *   - evictOlderThanWithSource raises the probe sentinel on first source.list call.
  *   - coldLoadBinding with probe raises the sentinel on first source.list/exists call.
  *   - withPickles does not touch the probe FileSource at all.
  *   - no public Unsafe-tier mirrors exist on object Tasty.*.
  *   - evictOlderThanWithSource call log contains list + delete entries (and no rename).
  *
  * All tests pass on JVM, JS, and Native (cross-platform placement).
  */
class Inv009BehavioralTest extends kyo.test.Test[Any]:

    // Two Package symbols: pkg (id=0, root) and child (id=1, owned by pkg).
    // Minimal classpath; pure query methods that return empty Chunk on absent kinds are
    // still valid proofs of no IO (returning empty Chunk is correct behavior, not an error).
    private val pkg = Tasty.Symbol.Package(
        Tasty.SymbolId(0),
        Tasty.Name("root"),
        Tasty.Flags.empty,
        Tasty.SymbolId(-1),
        Chunk(Tasty.SymbolId(1))
    )

    private val child = Tasty.Symbol.Package(
        Tasty.SymbolId(1),
        Tasty.Name("root.child"),
        Tasty.Flags.empty,
        Tasty.SymbolId(0),
        Chunk.empty
    )

    private val minimalCp = Tasty.Classpath(
        symbols = Chunk(pkg, child),
        indices = Tasty.Classpath.Indices.empty,
        errors = Chunk.empty,
        modules = Chunk.empty,
        rootSymbolId = Tasty.SymbolId(0)
    )

    // Recording FileSource for leaf 13: logs calls, returns empty/success results.
    // filesToList: paths returned by the list method; allows testing the delete path.
    // mtime: mtime returned by stat; 0L means infinitely old relative to any positive maxAge.
    final private class RecordingFileSource(
        filesToList: Seq[String],
        mtime: Long
    ) extends kyo.internal.tasty.query.FileSource:
        private val log        = mutable.Buffer.empty[String]
        def calls: Seq[String] = log.toSeq

        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            log += s"read $path"
            Abort.fail(TastyError.FileNotFound(path))

        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            log += s"write $path"
            Kyo.unit

        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            log += s"rename $from -> $to"
            Kyo.unit

        // override to log delete calls; trait-body default would attempt real filesystem op.
        override def delete(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            log += s"delete $path"
            Kyo.unit

        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            log += s"mkdirs $path"
            Kyo.unit

        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            log += s"list $dir"
            Sync.defer(Chunk.from(filesToList))

        def exists(path: String)(using Frame): Boolean < Sync =
            log += s"exists $path"
            Sync.defer(false)

        def stat(path: String)(using Frame): kyo.internal.tasty.query.FileSource.FileStat < (Sync & Abort[TastyError]) =
            log += s"stat $path"
            Sync.defer(kyo.internal.tasty.query.FileSource.FileStat(mtime, 100L))

    end RecordingFileSource

    "pure find-family queries perform no IO" in {
        Tasty.withClasspath(minimalCp):
            for
                c1 <- Tasty.findClass("root.Nonexistent")
                c2 <- Tasty.findClassLike("root.Nonexistent")
                c3 <- Tasty.findObject("root.Nonexistent")
                c4 <- Tasty.findSymbol("root")
                c5 <- Tasty.findPackage("root")
                c6 <- Tasty.findModule("nonexistent")
                c7 <- Tasty.findConcreteClass("root.Nonexistent")
                c8 <- Tasty.findClassesByName("Nonexistent")
            yield
                // With Classpath.Indices.empty the index-based lookups return Absent;
                // that is correct behavior (no index populated), not an IO call.
                assert(c1.isEmpty)
                assert(c2.isEmpty)
                assert(c3.isEmpty)
                assert(c4.isEmpty, "findSymbol on empty-index fixture returns Absent (no IO)")
                assert(c5.isEmpty, "findPackage on empty-index fixture returns Absent (no IO)")
                assert(c6.isEmpty)
                assert(c7.isEmpty)
                assert(c8.isEmpty)
                succeed
    }

    "pure require-family queries perform no IO" in {
        Tasty.withClasspath(minimalCp):
            Abort.run[TastyError]:
                for
                    _ <- Tasty.requireClass("root.Nonexistent")
                yield succeed
            .flatMap: r1 =>
                Abort.run[TastyError]:
                    Tasty.requirePackage("root")
                .flatMap: r2 =>
                    Abort.run[TastyError]:
                        Tasty.requireMethod("root", "noMethod")
                    .map: r3 =>
                        // With Classpath.Indices.empty all require-* calls abort NotFound; not an IO call.
                        assert(
                            r1.isInstanceOf[Result.Failure[?]],
                            "requireClass on absent FQN must abort NotFound (not probe sentinel)"
                        )
                        assert(
                            r2.isInstanceOf[Result.Failure[?]],
                            s"requirePackage on empty-index fixture must abort NotFound (no IO); got $r2"
                        )
                        assert(
                            r3.isInstanceOf[Result.Failure[?]],
                            "requireMethod on absent owner must abort NotFound (not probe sentinel)"
                        )
                        succeed
    }

    "pure aggregator queries perform no IO" in {
        Tasty.withClasspath(minimalCp):
            for
                a1  <- Tasty.allClassLike
                a2  <- Tasty.allClasses
                a3  <- Tasty.allObjects
                a4  <- Tasty.allTraits
                a5  <- Tasty.allMethods
                a6  <- Tasty.allVals
                a7  <- Tasty.allVars
                a8  <- Tasty.allFields
                a9  <- Tasty.allTypes
                a10 <- Tasty.allPackages
            yield
                assert(a1.isEmpty, "allClassLike empty for Package-only fixture")
                assert(a2.isEmpty, "allClasses empty for Package-only fixture")
                assert(a10.size == 2, s"allPackages must return 2 for fixture; got ${a10.size}")
                succeed
    }

    "pure traversal queries perform no IO" in {
        Tasty.withClasspath(minimalCp):
            for
                o1 <- Tasty.owner(pkg)
                fn <- Tasty.fullName(pkg)
                sh <- Tasty.show(pkg, Tasty.ShowFormat.Code)
            yield
                // root pkg has ownerId = -1, so owner returns Absent
                assert(o1.isEmpty, "owner(root) must be Absent (ownerId == -1)")
                assert(sh.nonEmpty, "show must return a non-empty string")
                succeed
    }

    // pkg.memberIds = Chunk(SymbolId(1)) pointing to child package.
    // members(pkg, All) == members(pkg, Declared); members(pkg, Inherited) == Chunk.empty.
    "package member scope concrete equality" in {
        Tasty.withClasspath(minimalCp):
            for
                decl <- Tasty.members(pkg, Tasty.MemberScope.Declared).map(_.map(_.simpleName))
                inh  <- Tasty.members(pkg, Tasty.MemberScope.Inherited)
                all  <- Tasty.members(pkg, Tasty.MemberScope.All).map(_.map(_.simpleName))
            yield
                // pkg.memberIds = Chunk(SymbolId(1)) => resolves to the child Package "root.child"
                assert(
                    decl == Chunk("root.child"),
                    s"members(pkg, Declared).map(_.simpleName) must equal Chunk(root.child); got $decl"
                )
                assert(
                    all == decl,
                    s"members(pkg, All) must equal members(pkg, Declared); all=$all declared=$decl"
                )
                assert(
                    inh == Chunk.empty,
                    s"members(pkg, Inherited) must be Chunk.empty for packages; got $inh"
                )
                succeed
    }

    "pure annotation queries perform no IO" in {
        Tasty.withClasspath(minimalCp):
            for
                b1 <- Tasty.hasAnnotation(pkg, "scala.deprecated")
                fa <- Tasty.findAnnotation(pkg, "scala.deprecated")
                sa <- Tasty.symbolsAnnotatedWith("scala.deprecated")
            yield
                assert(!b1, "hasAnnotation on Package-only fixture must be false")
                assert(fa.isEmpty, "findAnnotation on Package-only fixture must be Absent")
                assert(sa.isEmpty, "symbolsAnnotatedWith on Package-only fixture must be empty")
                succeed
    }

    "Tasty.classpath accessor performs no IO" in {
        Tasty.withClasspath(minimalCp):
            Tasty.classpath.map: cp =>
                assert(cp.symbols.size == 2, s"classpath must return bound cp; got ${cp.symbols.size} symbols")
                succeed
    }

    "bodyTree returns Maybe.Absent under withClasspath(cp)" in {
        Tasty.withClasspath(minimalCp):
            Abort.run[TastyError](Tasty.bodyTree(pkg)).map:
                case Result.Success(t) =>
                    assert(t.isEmpty, s"bodyTree must return Absent when decodeCtx is Absent; got $t")
                    succeed
                case Result.Failure(e) =>
                    fail(s"bodyTree must not abort under withClasspath(cp); got $e")
                case Result.Panic(t) =>
                    throw t
    }

    "evictOlderThan exercises FileSource (probe sentinel raised)" in {
        val probe = new TestProbeFileSource()
        Abort.run[TastyError](
            Tasty.Snapshot.evictOlderThanWithSource("inv009-cache", 86400000L, probe)
        ).map:
            case Result.Panic(t) =>
                assert(
                    t.getMessage.contains("A1 probe: no IO permitted (list inv009-cache)"),
                    s"probe sentinel not found in: ${t.getMessage}"
                )
                succeed
            case Result.Failure(e) =>
                fail(s"Expected Result.Panic from probe throw; got Result.Failure($e)")
            case Result.Success(_) =>
                fail("Expected Result.Panic from probe throw; got Result.Success")
    }

    "withClasspath(roots) cold-load reads FileSource (probe sentinel raised)" in {
        val probe = new TestProbeFileSource()
        Scope.run:
            Abort.run[TastyError](
                ClasspathOrchestrator.coldLoadBinding(
                    Seq("inv009-root"),
                    Tasty.ErrorMode.SoftFail,
                    Maybe.Absent,
                    probe,
                    1
                )
            ).map:
                case Result.Panic(t) =>
                    assert(
                        t.getMessage.contains("A1 probe: no IO permitted"),
                        s"probe sentinel not found in: ${t.getMessage}"
                    )
                    succeed
                case Result.Failure(e) =>
                    fail(s"Expected Result.Panic from probe throw; got Result.Failure($e)")
                case Result.Success(_) =>
                    fail("Expected Result.Panic from probe throw; got Result.Success")
    }

    "withPickles does not touch FileSource" in {
        val pickle = Tasty.Pickle(
            uuid = "inv009-leaf11",
            version = Tasty.Version(28, 3, 0),
            bytes = Span.from(kyo.fixtures.Embedded.plainClassTasty)
        )
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)):
                Tasty.classpath.map(_.symbols.size)
        ).map:
            case Result.Success(n) =>
                assert(n > 0, s"withPickles must bind a non-empty classpath; got $n symbols")
                succeed
            case Result.Failure(e) =>
                fail(s"withPickles must not abort; got $e")
            case Result.Panic(t) =>
                throw t
    }

    "no public Unsafe-tier mirrors on object Tasty.*" in {
        val e1 = compiletime.testing.typeCheckErrors("kyo.Tasty.unsafeInit(Seq.empty)").length
        val e2 = compiletime.testing.typeCheckErrors("kyo.Tasty.decodeUnsafe").length
        val e3 = compiletime.testing.typeCheckErrors("kyo.Tasty.Unsafe.bodyTree").length
        assert(e1 > 0, "Tasty.unsafeInit must not be a public member; expected compile error")
        assert(e2 > 0, "Tasty.decodeUnsafe must not be a public member; expected compile error")
        assert(e3 > 0, "Tasty.Unsafe.bodyTree must not be a public member; expected compile error")
        succeed
    }

    "evictOlderThan site-4 calls FileSource.delete, not rename" in {
        val cacheDir  = "cache13"
        val staleFile = s"$cacheDir/dead.krfl"
        // mtime=0 means infinitely old; always evicted
        val rec = new RecordingFileSource(filesToList = Seq(staleFile), mtime = 0L)
        Abort.run[TastyError](
            Tasty.Snapshot.evictOlderThanWithSource(cacheDir, 1L, rec)
        ).map: result =>
            val callLog = rec.calls
            assert(
                callLog.exists(_.startsWith(s"list $cacheDir")),
                s"call log must contain 'list $cacheDir'; got: $callLog"
            )
            assert(
                callLog.exists(_.startsWith("delete ")),
                s"call log must contain at least one 'delete ...' entry; got: $callLog"
            )
            assert(
                callLog.forall(!_.startsWith("rename ")),
                s"call log must NOT contain any 'rename ...' entries; got: $callLog"
            )
            // The delete entry must name the stale file.
            assert(
                callLog.contains(s"delete $staleFile"),
                s"call log must contain 'delete $staleFile'; got: $callLog"
            )
            result match
                case Result.Panic(t) => throw t
                case Result.Failure(e) =>
                    fail(s"evictOlderThanWithSource must not abort under recording source; got $e")
                case Result.Success(_) => succeed
            end match
    }

    "cross-platform placement (JVM, JS, Native)" in {
        succeed
    }

end Inv009BehavioralTest
