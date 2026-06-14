package kyo

import kyo.internal.TestClasspaths

/** Fidelity tests for opaque type detection and dual fully-qualified name indexing.
  *
  * Exercises dual-index registration for OpaqueType symbols in
  * `ClasspathOrchestrator.mergeOneInto`, so both the binary fully-qualified name
  * (`kyo.Maybe$package$.Maybe`) and the source fully-qualified name (`kyo.Maybe`) point to the same
  * `Symbol.OpaqueType` instance.
  *
  * OpaqueFixture (kyo-tasty/fixtures/shared) provides two opaque types (`kyo.fixtures.Micros` and
  * `kyo.fixtures.Millis`) in the embedded fixture set. A package-level opaque type `X` in
  * `kyo.fixtures` has binary fully-qualified name `kyo.fixtures.OpaqueFixture$package$.X` and source fully-qualified name
  * `kyo.fixtures.X`; both must be findable.
  */
class OpaqueTypeFidelityTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "classpath.findSymbol(kyo.fixtures.Micros) returns Present(Symbol.OpaqueType)" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            classpath.findSymbol("kyo.fixtures.Micros") match
                case Present(symbol: Tasty.Symbol.OpaqueType) =>
                    assert(
                        symbol.name.asString == "Micros",
                        s"Expected symbol name 'Micros', got '${symbol.name.asString}'"
                    )
                    succeed
                case Present(other) =>
                    val relatedKeys = classpath.indices.byFullName.toMap.keys.filter(k => k.contains("Micros")).toSeq.sorted.take(5)
                    fail(
                        s"classpath.findSymbol(kyo.fixtures.Micros) returned Present but wrong kind: ${other.getClass.getSimpleName}. " +
                            s"Related fullNameIndex keys: ${relatedKeys.mkString(", ")}"
                    )
                case Absent =>
                    val relatedKeys = classpath.indices.byFullName.toMap.keys.filter(k => k.contains("Micros")).toSeq.sorted.take(5)
                    fail(
                        s"classpath.findSymbol(kyo.fixtures.Micros) returned Absent. " +
                            s"Related fullNameIndex keys: ${relatedKeys.mkString(", ")}. " +
                            "Check that OpaqueFixture TASTy bytes are in Embedded.scala and TestClasspaths loads them."
                    )
        }
    }

    "classpath.findSymbol(kyo.fixtures.Micros) and kyo.fixtures.Millis return Present(Symbol.OpaqueType)" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val microsSym  = classpath.findSymbol("kyo.fixtures.Micros")
            val millisSym  = classpath.findSymbol("kyo.fixtures.Millis")
            val microsKeys = classpath.indices.byFullName.toMap.keys.filter(k => k.contains("Micros")).toSeq.sorted.take(5)
            val millisKeys = classpath.indices.byFullName.toMap.keys.filter(k => k.contains("Millis")).toSeq.sorted.take(5)
            assert(
                microsSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"classpath.findSymbol(kyo.fixtures.Micros) expected Present(OpaqueType), got $microsSym. " +
                    s"Related fullNameIndex keys: ${microsKeys.mkString(", ")}"
            )
            assert(
                millisSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"classpath.findSymbol(kyo.fixtures.Millis) expected Present(OpaqueType), got $millisSym. " +
                    s"Related fullNameIndex keys: ${millisKeys.mkString(", ")}"
            )
            succeed
        }
    }

    "opaque type is findable via its binary fully-qualified name kyo.fixtures.OpaqueFixture$package$.Micros" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            // The source fully-qualified name must resolve to OpaqueType (this is the primary pin).
            val sourceResult = classpath.findSymbol("kyo.fixtures.Micros")
            assert(
                sourceResult.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"classpath.findSymbol(kyo.fixtures.Micros) must return OpaqueType. " +
                    s"Related fullNameIndex keys: ${classpath.indices.byFullName.toMap.keys.filter(_.contains("Micros")).toSeq.sorted.take(5).mkString(", ")}"
            )
            // The binary fully-qualified name must also be indexable; the dual-index registers both.
            val binaryResult = classpath.findSymbol("kyo.fixtures.OpaqueFixture$package$.Micros")
            assert(
                binaryResult.isDefined,
                s"classpath.findSymbol(kyo.fixtures.OpaqueFixture$$package$$.Micros) returned Absent. " +
                    s"Dual-index must register binary fully-qualified name for package-level opaque types. " +
                    s"Related fullNameIndex keys: ${classpath.indices.byFullName.toMap.keys.filter(_.contains("Micros")).toSeq.sorted.take(5).mkString(", ")}"
            )
            succeed
        }
    }

    "kyo.fixtures.Micros symbol is Symbol.OpaqueType, not Symbol.Val" in {
        import Tasty.Name.asString
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val microsSym = classpath.findSymbol("kyo.fixtures.Micros")
            assert(
                microsSym.exists(_.isInstanceOf[Tasty.Symbol.OpaqueType]),
                s"classpath.findSymbol(kyo.fixtures.Micros) must return OpaqueType, got: $microsSym. " +
                    s"Related fullNameIndex keys: ${classpath.indices.byFullName.toMap.keys.filter(_.contains("Micros")).toSeq.sorted.take(5).mkString(", ")}"
            )
            val allOpaqueNames = classpath.allOpaqueTypes.map(_.name.asString).toSet
            assert(
                allOpaqueNames.contains("Micros"),
                s"classpath.allOpaqueTypes must contain a symbol named 'Micros'. " +
                    s"Found opaque type names: ${allOpaqueNames.toSeq.sorted.take(10).mkString(", ")}"
            )
            succeed
        }
    }

end OpaqueTypeFidelityTest
