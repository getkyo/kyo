package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for opaque type detection and dual FQN indexing.
  *
  * Pins findings  ,  , and   All leaves were PENDING until un-pended them by adding dual-index registration for
  * OpaqueType symbols in `ClasspathOrchestrator.mergeOneInto`, so that both the binary FQN (`kyo.Maybe$package$.Maybe`) and the source FQN
  * (`kyo.Maybe`) point to the same `Symbol.OpaqueType` instance.
  *
  * all 4 leaves ungated. OpaqueFixture (kyo-tasty-fixtures/shared) provides two opaque types (`kyo.fixtures.Micros`
  * and `kyo.fixtures.Millis`) in the embedded fixture set. Leaves 1-4 are rewritten to use these embedded opaque types instead of kyo.Maybe,
  * kyo.Result, and kyo.Duration from kyo-data. The dual-FQN indexing property is structurally identical: a package-level opaque type
  * `X` in `kyo.fixtures` has binary FQN `kyo.fixtures.OpaqueFixture$package$.X` and source FQN `kyo.fixtures.X`; both must be findable.
  */
class OpaqueTypeFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    //   / leaf 1: embedded-micros
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findSymbol("kyo.fixtures.Micros")
    // Then: post-fix Present(s: Symbol.OpaqueType) with s.name.asString == "Micros"
    // Cross-platform: kyo.fixtures.Micros is in the embedded fixture set (OpaqueFixture.scala) on all platforms.
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

    //   leaf 2: embedded-micros-and-millis
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findSymbol("kyo.fixtures.Micros") and cp.findSymbol("kyo.fixtures.Millis")
    // Then: post-fix both return Present(_: Symbol.OpaqueType)
    // Cross-platform: both Micros and Millis are in the embedded fixture set (OpaqueFixture.scala) on all platforms.
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

    // Q-003 /   leaf 3: binary-fqn-still-findable
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findSymbol("kyo.fixtures.OpaqueFixture$package$.Micros")
    // Then: post-fix Present(_) (the binary FQN remains resolvable per HARD RULE 4 layer-don't-restrict)
    // Cross-platform: OpaqueFixture is in the embedded fixture set on all platforms.
    "Q-003 : opaque type is findable via its binary FQN kyo.fixtures.OpaqueFixture$package$.Micros" in {
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

    // leaf 4: no-opaque-as-val
    // Given: any classpath loaded via TestClasspaths.withClasspath (JVM: real stdlib + fixtures; JS/Native: embedded fixtures)
    // When: calling cp.findSymbol("kyo.fixtures.Micros") and examining cp.allOpaqueTypes for a "Micros" entry
    // Then: post-fix cp.findSymbol("kyo.fixtures.Micros") returns a Symbol.OpaqueType (not Symbol.Val);
    //       cp.allOpaqueTypes includes a symbol named "Micros"
    // Cross-platform: Micros is in the embedded fixture set (OpaqueFixture.scala) on all platforms.
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
