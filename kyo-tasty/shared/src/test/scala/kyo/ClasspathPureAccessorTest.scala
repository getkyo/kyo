package kyo

/** Tests for Tasty.Classpath pure accessors: findClass, symbols, topLevelClasses, and post-Scope access. */
class ClasspathPureAccessorTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    "findClass returns Present for a known fully-qualified name" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    classpath.findClass("kyo.fixtures.PlainClass")
                }
            }
        ).map {
            case Result.Success(Present(symbol)) =>
                assert(
                    symbol.name.asString == "PlainClass",
                    s"Expected name 'PlainClass' but got '${symbol.name.asString}'"
                )
            case Result.Success(Absent) =>
                fail("Expected Present(symbol) for kyo.fixtures.PlainClass but got Absent")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "symbols is non-empty after open" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map(_.symbols)
            }
        ).map {
            case Result.Success(syms) =>
                assert(syms.nonEmpty, "symbols should be non-empty after loading a classpath")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "Tasty.Classpath is accessible after scope exits (no Closed state)" in {
        var capturedCp: Tasty.Classpath = null
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { classpath =>
                    capturedCp = classpath
                }
            }
        ).map {
            case Result.Success(_) =>
                assert(capturedCp != null, "Classpath should have been captured")
                assert(capturedCp.symbols.nonEmpty, "symbols should still be accessible after scope exits")
                assert(capturedCp.errors.isEmpty, "errors should be empty for clean classpath")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "topLevelClasses is non-empty after open" in {
        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map(_.topLevelClasses)
            }
        ).map {
            case Result.Success(classes) =>
                assert(classes.nonEmpty, "topLevelClasses should be non-empty")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

end ClasspathPureAccessorTest
