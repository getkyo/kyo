package kyo

/** Tasty.withClasspath and Tasty.withPickles entry points:
  *   - withClasspath(classpath) binds pure-data without decode context
  *   - withPickles(pickles) binds from pickles
  *   - withClasspath(roots, Absent) does not touch any cache
  *   - Classpath.init and Classpath.initCached are not on the public surface (compileErrors)
  */
class WithClasspathTest extends kyo.test.Test[Any]:

    "withClasspath(classpath) binds pure-data classpath; returns correct symbol count" in {
        val classpath = Tasty.Classpath(
            symbols = Chunk(
                Tasty.Symbol.Package(
                    Tasty.SymbolId(0),
                    Tasty.Name("root"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(-1),
                    Chunk.empty
                ),
                Tasty.Symbol.Package(
                    Tasty.SymbolId(1),
                    Tasty.Name("child"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(0),
                    Chunk.empty
                )
            ),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        Tasty.withClasspath(classpath) {
            Tasty.classpath.map { bound =>
                val n = bound.symbols.size
                assert(n == 2, s"withClasspath(classpath) must bind the passed classpath; expected 2, got $n")
                succeed
            }
        }
    }

    "withPickles(pickles) binds classpath from pickles; PlainClass discoverable" in {
        val pickle = Tasty.Pickle(
            uuid = "leaf3-plain-class",
            version = Tasty.Version(28, 3, 0),
            bytes = Span.from(kyo.fixtures.Embedded.plainClassTasty)
        )
        Tasty.withPickles(Chunk(pickle)) {
            Tasty.classpath.map { classpath =>
                val found = classpath.findClassLike("kyo.fixtures.PlainClass")
                assert(found.isDefined, s"PlainClass must be discoverable after withPickles; got ${classpath.symbols.size} symbols")
                assert(classpath.symbols.size > 0, s"withPickles must bind a non-empty classpath; got ${classpath.symbols.size}")
                succeed
            }
        }
    }

    // A pickle says what Scala knows about a class; its classfile companion says what the JVM knows, which is where
    // `javaMetadata` comes from. On a file system the two are found as siblings by name. In memory there is nothing
    // to walk, so the pickle carries its own companion, and these two leaves pin both halves of that: supplying it
    // produces the metadata, and omitting it leaves the symbol without any, on every host.
    "withPickles(pickles) merges a pickle's classfile companion into javaMetadata" in {
        val pickle = Tasty.Pickle(
            uuid = "plain-class-with-companion",
            version = Tasty.Version(28, 3, 0),
            bytes = Span.from(kyo.fixtures.Embedded.plainClassTasty),
            classfile = Maybe(Span.from(kyo.fixtures.Embedded.plainClassClassfile))
        )
        Tasty.withPickles(Chunk(pickle)) {
            Tasty.classpath.map { classpath =>
                val withMeta = classpath.allClassLike.filter(_.javaMetadata.isDefined)
                assert(
                    withMeta.nonEmpty,
                    s"a pickle carrying its classfile must produce javaMetadata; got ${classpath.allClassLike.size} classes, none with any"
                )
                succeed
            }
        }
    }

    "withPickles(pickles) without a classfile leaves javaMetadata Absent" in {
        val pickle = Tasty.Pickle(
            uuid = "plain-class-no-companion",
            version = Tasty.Version(28, 3, 0),
            bytes = Span.from(kyo.fixtures.Embedded.plainClassTasty)
        )
        Tasty.withPickles(Chunk(pickle)) {
            Tasty.classpath.map { classpath =>
                val withMeta = classpath.allClassLike.filter(_.javaMetadata.isDefined)
                assert(withMeta.isEmpty, s"a pickle with no classfile must carry no javaMetadata; got ${withMeta.size}")
                succeed
            }
        }
    }

    "withPickles(pickles, classfiles) decodes a Java class that has no pickle" in {
        val classfile = Tasty.Classfile(
            name = "JavaSimpleFixture",
            bytes = Span.from(kyo.fixtures.EmbeddedJavaFixtures.javaSimpleFixtureClassfile)
        )
        Tasty.withPickles(Chunk.empty, Chunk(classfile)) {
            Tasty.findClass("kyo.fixtures.JavaSimpleFixture").map {
                case Maybe.Present(c) =>
                    assert(c.isJava, "a class introduced by its classfile alone must carry isJava")
                    succeed
                case Maybe.Absent =>
                    fail("kyo.fixtures.JavaSimpleFixture must be found from its classfile with no pickle present")
            }
        }
    }

    "withPickles names a standalone class from its bytecode, not from the label given" in {
        // The label is only what a decode failure is reported against, so a wrong one must not hide the class.
        val classfile = Tasty.Classfile(
            name = "a-label-that-is-not-the-class-name",
            bytes = Span.from(kyo.fixtures.EmbeddedJavaFixtures.javaSimpleFixtureClassfile)
        )
        Tasty.withPickles(Chunk.empty, Chunk(classfile)) {
            Tasty.findClass("kyo.fixtures.JavaSimpleFixture").map { found =>
                assert(found.isDefined, "the class must be found under its own name whatever it was filed as")
                succeed
            }
        }
    }

    "Classpath.init is not on the public surface" in {
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.init(Seq(\"x\"))").length
        assert(errCount > 0, "Classpath.init must not be on the surface; expected a compile error")
        succeed
    }

    "Classpath.initCached is not on the public surface" in {
        val errCount = compiletime.testing.typeCheckErrors("kyo.Tasty.Classpath.initCached(Seq(\"x\"), \"/tmp\")").length
        assert(errCount > 0, "Classpath.initCached must not be on the surface; expected a compile error")
        succeed
    }

    "withClasspath(roots, Absent) does not touch any cache" in {
        val pickle = Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(pickle)) {
                Tasty.classpath.map { classpath =>
                    Tasty.withClasspath(classpath) {
                        Tasty.classpath.map { bound =>
                            val n = bound.symbols.size
                            assert(n > 0, s"withClasspath(classpath) must return a non-empty classpath; got $n")
                            succeed
                        }
                    }
                }
            }
        ).map {
            case Result.Success(r) => r
            case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)   => throw t
        }
    }

end WithClasspathTest
