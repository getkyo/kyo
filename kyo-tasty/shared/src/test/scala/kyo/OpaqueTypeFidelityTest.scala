package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for opaque type detection and dual FQN indexing.
  *
  * Exercises dual-index registration for OpaqueType symbols in
  * `ClasspathOrchestrator.mergeOneInto`, so both the binary FQN
  * (`kyo.Maybe$package$.Maybe`) and the source FQN (`kyo.Maybe`) point to the same
  * `Symbol.OpaqueType` instance.
  *
  * OpaqueFixture (kyo-tasty-fixtures/shared) provides two opaque types (`kyo.fixtures.Micros` and
  * `kyo.fixtures.Millis`) in the embedded fixture set. A package-level opaque type `X` in
  * `kyo.fixtures` has binary FQN `kyo.fixtures.OpaqueFixture$package$.X` and source FQN
  * `kyo.fixtures.X`; both must be findable.
  */
class OpaqueTypeFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "cp.findSymbol(kyo.fixtures.Micros) returns Present(Symbol.OpaqueType)" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            cp.findSymbol("kyo.fixtures.Micros") match
                case Present(sym: Tasty.Symbol.OpaqueType) =>
                    assert(
                        sym.name.asString == "Micros",
                        s"Expected symbol name 'Micros', got '${sym.name.asString}'"
                    )
                    succeed
                case Present(other) =>
                    val relatedKeys = cp.indices.byFqn.toMap.keys.filter(k => k.contains("Micros")).toSeq.sorted.take(5)
                    fail(
                        s"cp.findSymbol(kyo.fixtures.Micros) returned Present but wrong kind: ${other.getClass.getSimpleName}. " +
                            s"Related fqnIndex keys: ${relatedKeys.mkString(", ")}"
                    )
                case Absent =>
                    val relatedKeys = cp.indices.byFqn.toMap.keys.filter(k => k.contains("Micros")).toSeq.sorted.take(5)
                    fail(
                        s"cp.findSymbol(kyo.fixtures.Micros) returned Absent. " +
                            s"Related fqnIndex keys: ${relatedKeys.mkString(", ")}. " +
                            "Check that OpaqueFixture TASTy bytes are in Embedded.scala and TestClasspaths loads them."
                    )
    }

    "cp.findSymbol(kyo.fixtures.Micros) and kyo.fixtures.Millis return Present(Symbol.OpaqueType)" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val microsSym  = cp.findSymbol("kyo.fixtures.Micros")
            val millisSym  = cp.findSymbol("kyo.fixtures.Millis")
            val microsKeys = cp.indices.byFqn.toMap.keys.filter(k => k.contains("Micros")).toSeq.sorted.take(5)
            val millisKeys = cp.indices.byFqn.toMap.keys.filter(k => k.contains("Millis")).toSeq.sorted.take(5)
            assert(
                microsSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"cp.findSymbol(kyo.fixtures.Micros) expected Present(OpaqueType), got $microsSym. " +
                    s"Related fqnIndex keys: ${microsKeys.mkString(", ")}"
            )
            assert(
                millisSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"cp.findSymbol(kyo.fixtures.Millis) expected Present(OpaqueType), got $millisSym. " +
                    s"Related fqnIndex keys: ${millisKeys.mkString(", ")}"
            )
            succeed
    }

    "opaque type is findable via its binary FQN kyo.fixtures.OpaqueFixture$package$.Micros" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            // The source FQN must resolve to OpaqueType (this is the primary pin).
            val sourceResult = cp.findSymbol("kyo.fixtures.Micros")
            assert(
                sourceResult.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"cp.findSymbol(kyo.fixtures.Micros) must return OpaqueType. " +
                    s"Related fqnIndex keys: ${cp.indices.byFqn.toMap.keys.filter(_.contains("Micros")).toSeq.sorted.take(5).mkString(", ")}"
            )
            // The binary FQN must also be indexable; the dual-index registers both.
            val binaryResult = cp.findSymbol("kyo.fixtures.OpaqueFixture$package$.Micros")
            assert(
                binaryResult.isDefined,
                s"cp.findSymbol(kyo.fixtures.OpaqueFixture$$package$$.Micros) returned Absent. " +
                    s"Dual-index must register binary FQN for package-level opaque types. " +
                    s"Related fqnIndex keys: ${cp.indices.byFqn.toMap.keys.filter(_.contains("Micros")).toSeq.sorted.take(5).mkString(", ")}"
            )
            succeed
    }

    "kyo.fixtures.Micros symbol is Symbol.OpaqueType, not Symbol.Val" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map: cp =>
            val microsSym = cp.findSymbol("kyo.fixtures.Micros")
            assert(
                microsSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"cp.findSymbol(kyo.fixtures.Micros) must return OpaqueType, got: $microsSym. " +
                    s"Related fqnIndex keys: ${cp.indices.byFqn.toMap.keys.filter(_.contains("Micros")).toSeq.sorted.take(5).mkString(", ")}"
            )
            val allOpaqueNames = cp.allOpaqueTypes.map(_.name.asString).toSet
            assert(
                allOpaqueNames.contains("Micros"),
                s"cp.allOpaqueTypes must contain a symbol named 'Micros'. " +
                    s"Found opaque type names: ${allOpaqueNames.toSeq.sorted.take(10).mkString(", ")}"
            )
            succeed
    }

end OpaqueTypeFidelityTest
