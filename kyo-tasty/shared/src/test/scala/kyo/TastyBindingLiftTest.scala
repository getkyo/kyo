package kyo

import kyo.internal.TestClasspaths
import kyo.internal.tasty.query.Binding
import kyo.internal.tasty.query.DecodeContext

/** Tests for `Tasty.bindingLocal` and `Tasty.global`: the two active-binding handles on object Tasty.
  *
  * Covers: binding access, default seed, global lazy singleton, classpath scoping, nested scoping, and
  * the private[kyo] visibility contract verified from within and outside the kyo package.
  */
class TastyBindingLiftTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // The fully-qualified name kyo.internal.tasty.query.TastyState must not resolve.
    "kyo.internal.tasty.query.TastyState does not exist" in {
        val errors = compiletime.testing.typeCheckErrors("kyo.internal.tasty.query.TastyState")
        assert(errors.nonEmpty, "kyo.internal.tasty.query.TastyState must not resolve")
        succeed
    }

    // Tasty.bindingLocal is accessible from package kyo. Outside any withClasspath scope,
    // the default seed is Maybe.Absent.
    "bindingLocal default seed is Maybe.Absent outside withClasspath scope" in {
        Tasty.bindingLocal.use { mbind =>
            assert(mbind == Maybe.Absent, s"Expected Maybe.Absent; got $mbind")
            succeed
        }
    }

    // Tasty.global is a lazy val singleton; two accesses return the same Binding reference.
    "Tasty.global lazy val returns the same Binding instance on every access".onlyJvm in {
        TestClasspaths.forceGlobalNarrowed()
        val b1 = Tasty.global
        val b2 = Tasty.global
        assert(
            b1 eq b2,
            s"Tasty.global must return the same Binding instance (lazy val singleton); got two distinct objects"
        )
        succeed
    }

    // On JS and Native, Tasty.global is Binding.empty; reference equality still holds.
    "Tasty.global is Binding.empty singleton on JS and Native".notJvm in {
        val b1 = Tasty.global
        val b2 = Tasty.global
        assert(b1 eq b2, s"Tasty.global must return the same Binding.empty instance on JS and Native")
        succeed
    }

    // On JVM, Tasty.classpath outside any withClasspath scope falls back to Tasty.global's classpath.
    "classpath outside withClasspath scope returns global classpath on JVM".onlyJvm in {
        TestClasspaths.forceGlobalNarrowed()
        Tasty.classpath.map { classpath =>
            val globalClasspath = Tasty.global.classpath
            assert(
                classpath eq globalClasspath,
                s"Expected Tasty.global classpath (JVM fallback), got a different Classpath instance"
            )
            succeed
        }
    }

    // On JS, Tasty.global is Binding.empty; Tasty.classpath returns an empty Classpath.
    "classpath outside withClasspath scope returns empty Classpath on JS".onlyJs in {
        Tasty.classpath.map { classpath =>
            assert(
                classpath.symbols.isEmpty,
                s"JS Tasty.global classpath must be empty (Binding.empty); got ${classpath.symbols.size} symbols"
            )
            succeed
        }
    }

    // withClasspath(classpath) binds a specific classpath; Tasty.classpath inside the scope
    // returns the same instance by reference.
    "withClasspath(classpath) binds classpath and Tasty.classpath returns it" in {
        val fixture = Tasty.Classpath(
            symbols = Chunk(
                Tasty.Symbol.Package(
                    Tasty.SymbolId(0),
                    Tasty.Name("root"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(-1),
                    Chunk.empty
                )
            ),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        Tasty.withClasspath(fixture) {
            Tasty.classpath.map { bound =>
                assert(
                    bound eq fixture,
                    s"Tasty.classpath inside withClasspath must return the bound Classpath by reference; got a different instance"
                )
                succeed
            }
        }
    }

    // Nested withClasspath: Local innermost-wins semantics applies; the inner binding is active.
    "nested withClasspath: innermost binding is active" in {
        val classpath1 = Tasty.Classpath(
            symbols = Chunk(
                Tasty.Symbol.Package(
                    Tasty.SymbolId(0),
                    Tasty.Name("outer"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(-1),
                    Chunk.empty
                )
            ),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        val classpath2 = Tasty.Classpath(
            symbols = Chunk(
                Tasty.Symbol.Package(
                    Tasty.SymbolId(0),
                    Tasty.Name("inner"),
                    Tasty.Flags.empty,
                    Tasty.SymbolId(-1),
                    Chunk.empty
                )
            ),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        Tasty.withClasspath(classpath1) {
            Tasty.withClasspath(classpath2) {
                Tasty.classpath.map { inner =>
                    assert(
                        inner eq classpath2,
                        s"Nested withClasspath: inner Tasty.classpath must return classpath2 (Local innermost-wins semantics)"
                    )
                    succeed
                }
            }
        }
    }

    // Tasty.bindingLocal is private[kyo]; accessible from within package kyo. The compile-from-outside
    // restriction is verified by external/TastyBindingLocalVisibilityTest.scala.
    "Tasty.bindingLocal is private[kyo] and accessible from package kyo" in {
        val errors = compiletime.testing.typeCheckErrors("kyo.Tasty.bindingLocal : kyo.Local[kyo.Maybe[kyo.internal.tasty.query.Binding]]")
        assert(
            errors.isEmpty,
            s"Tasty.bindingLocal must be accessible from package kyo (private[kyo]); unexpected errors: $errors"
        )
        succeed
    }

    // Tasty.global is private[kyo]; accessible from within package kyo. The compile-from-outside
    // restriction is verified by external/TastyBindingLocalVisibilityTest.scala.
    "Tasty.global is private[kyo] and accessible from package kyo" in {
        val errors = compiletime.testing.typeCheckErrors("kyo.Tasty.global : kyo.internal.tasty.query.Binding")
        assert(
            errors.isEmpty,
            s"Tasty.global must be accessible from package kyo (private[kyo]); unexpected errors: $errors"
        )
        succeed
    }

end TastyBindingLiftTest
