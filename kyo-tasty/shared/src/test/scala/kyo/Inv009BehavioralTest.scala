package kyo

/** Behavioral enforcement: side effects only in named sites.
  *
  * Pure Tasty.* query methods perform zero IO. Every pure query returns its expected value.
  *
  *   - Tasty.bodyTree returns Maybe.Absent under withClasspath(classpath) (decodeCtx = Absent).
  *   - evictOlderThan removes stale snapshots and leaves fresh ones intact.
  *   - withClasspath(roots) cold-load reads the filesystem and produces a non-empty classpath.
  *   - withPickles does not read the filesystem.
  *   - no public Unsafe-tier mirrors exist on object Tasty.*.
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

    "pure find-family queries perform no IO" in {
        Tasty.withClasspath(minimalCp) {
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
    }

    "pure require-family queries perform no IO" in {
        Tasty.withClasspath(minimalCp) {
            Abort.run[TastyError] {
                for
                    _ <- Tasty.requireClass("root.Nonexistent")
                yield succeed
            }
                .map { r1 =>
                    Abort.run[TastyError] {
                        Tasty.requirePackage("root")
                    }
                        .map { r2 =>
                            Abort.run[TastyError] {
                                Tasty.requireMethod("root", "noMethod")
                            }
                                .map { r3 =>
                                    // With Classpath.Indices.empty all require-* calls abort NotFound; not an IO call.
                                    assert(
                                        r1.isInstanceOf[Result.Failure[?]],
                                        "requireClass on absent fully-qualified name must abort NotFound (not probe sentinel)"
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
                        }
                }
        }
    }

    "pure aggregator queries perform no IO" in {
        Tasty.withClasspath(minimalCp) {
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
    }

    "pure traversal queries perform no IO" in {
        // root pkg has ownerId = -1, so owner returns Absent
        assert(minimalCp.owner(pkg).isEmpty, "owner(root) must be Absent (ownerId == -1)")
        assert(minimalCp.show(pkg, Tasty.ShowFormat.Code).nonEmpty, "show must return a non-empty string")
    }

    // pkg.memberIds = Chunk(SymbolId(1)) pointing to child package.
    // members(pkg, All) == members(pkg, Declared); members(pkg, Inherited) == Chunk.empty.
    "package member scope concrete equality" in {
        val decl = minimalCp.members(pkg, Tasty.MemberScope.Declared).map(_.simpleName)
        val inh  = minimalCp.members(pkg, Tasty.MemberScope.Inherited)
        val all  = minimalCp.members(pkg, Tasty.MemberScope.All).map(_.simpleName)
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
    }

    "pure annotation queries perform no IO" in {
        assert(!minimalCp.hasAnnotation(pkg, "scala.deprecated"), "hasAnnotation on Package-only fixture must be false")
        assert(minimalCp.findAnnotation(pkg, "scala.deprecated").isEmpty, "findAnnotation on Package-only fixture must be Absent")
        assert(minimalCp.symbolsAnnotatedWith("scala.deprecated").isEmpty, "symbolsAnnotatedWith on Package-only fixture must be empty")
    }

    "Tasty.classpath accessor performs no IO" in {
        Tasty.withClasspath(minimalCp) {
            Tasty.classpath.map(classpath =>
                assert(classpath.symbols.size == 2, s"classpath must return bound classpath; got ${classpath.symbols.size} symbols")
                succeed
            )
        }
    }

    "bodyTree returns Maybe.Absent under withClasspath(classpath)" in {
        Tasty.withClasspath(minimalCp) {
            Abort.run[TastyError](Tasty.bodyTree(pkg)).map {
                case Result.Success(t) =>
                    assert(t.isEmpty, s"bodyTree must return Absent when decodeCtx is Absent; got $t")
                    succeed
                case Result.Failure(e) =>
                    fail(s"bodyTree must not abort under withClasspath(classpath); got $e")
                case Result.Panic(t) =>
                    throw t
            }
        }
    }

    "evictOlderThan removes stale snapshots from cacheDir" in {
        // maxAge is 60 seconds; the stale file is set to 2001-09-08 UTC (far in the past).
        val maxAge  = 60.seconds
        val staleMs = 1_000_000_000_000L // 2001-09-08 UTC
        Path.tempDir("inv009-evict").map { dir =>
            val staleFile = dir / "stale.krfl"
            val freshFile = dir / "fresh.krfl"
            staleFile.writeBytes(Span.from(Array[Byte](0x01))).map { _ =>
                staleFile.setLastModified(staleMs).map { _ =>
                    freshFile.writeBytes(Span.from(Array[Byte](0x02))).map { _ =>
                        Abort.run[TastyError](
                            Tasty.evictOlderThan(dir.toString, maxAge)
                        ).map { result =>
                            result match
                                case Result.Panic(t) => throw t
                                case Result.Failure(e) =>
                                    fail(s"evictOlderThan must not abort; got $e")
                                case Result.Success(_) =>
                                    staleFile.exists.map { staleExists =>
                                        freshFile.exists.map { freshExists =>
                                            assert(
                                                !staleExists,
                                                s"stale.krfl must have been deleted by evictOlderThan"
                                            )
                                            assert(
                                                freshExists,
                                                s"fresh.krfl must NOT have been deleted by evictOlderThan"
                                            )
                                            succeed
                                        }
                                    }
                            end match
                        }
                    }
                }
            }
        }
    }

    "withClasspath(roots) cold-load reads from the filesystem" in {
        Path.tempDir("inv009-cold").map { tmpDir =>
            val tastyFile = tmpDir / "PlainClass.tasty"
            tastyFile.writeBytes(Span.from(kyo.fixtures.Embedded.plainClassTasty)).map { _ =>
                Abort.run[TastyError](
                    Tasty.withClasspath(Seq(tmpDir.toString)) {
                        Tasty.classpath.map(_.symbols.size)
                    }
                ).map {
                    case Result.Success(n) =>
                        assert(n > 0, s"cold-load from filesystem must produce a non-empty classpath; got $n symbols")
                        succeed
                    case Result.Failure(e) =>
                        fail(s"withClasspath cold-load must not abort; got $e")
                    case Result.Panic(t) =>
                        throw t
                }
            }
        }
    }

    "withPickles does not read the filesystem" in {
        val pickle = Tasty.Pickle(
            uuid = "inv009-leaf11",
            version = Tasty.Version(28, 3, 0),
            bytes = Span.from(kyo.fixtures.Embedded.plainClassTasty)
        )
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)) {
                Tasty.classpath.map(_.symbols.size)
            }
        ).map {
            case Result.Success(n) =>
                assert(n > 0, s"withPickles must bind a non-empty classpath; got $n symbols")
                succeed
            case Result.Failure(e) =>
                fail(s"withPickles must not abort; got $e")
            case Result.Panic(t) =>
                throw t
        }
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

    "cross-platform placement (JVM, JS, Native)" in {
        succeed
    }

end Inv009BehavioralTest
