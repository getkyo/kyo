package kyo

import kyo.Json
import kyo.Tasty.SymbolId

/** (Cat 9 + Cat 18): exercises the Tasty.Java namespace and Schema derivations.
  *
  * Leaf 1: pathsResolveUnderTastyJava -- compile-time import probe for the new namespace.
  * Leaf 2: legacyPathsNoLongerResolve -- old kyo.Tasty.JavaAnnotation path must not resolve.
  * Leaf 3: recordComponentSchemaRoundTrip -- Java.RecordComponent round-trips via Schema.
  * Leaf 4: paramGroupSchemaRoundTrip -- Java.ParamGroup round-trips via Schema.
  * Leaf 5: enclosingMethodSchemaRoundTripUsesSchemaSymbol -- Java.EnclosingMethod round-trip; schemaSymbol in scope.
  * Leaf 6: moduleSubRecordsSchemaRoundTrip -- Java.Module.Requires/Exports/Opens/Provides round-trip.
  * Leaf 7: annotationMutualRecursionWorkaroundUnchangedSemantics -- Java.Annotation mutual-recursion round-trip.
  */
class TastyJavaNamespaceTest extends Test:

    // ── Leaf 1: pathsResolveUnderTastyJava ───────────────────────────────────
    // Given: imports of the new Tasty.Java.* paths.
    // When: the file compiles.
    // Then: every import resolves (compile-time proof).
    //
    // These are compile-time probes: if any type is missing, the file fails to compile.
    private val _probeAnnotation: Tasty.Java.Annotation =
        Tasty.Java.Annotation(
            Tasty.Symbol.Package(SymbolId(0), Tasty.Name("p"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty),
            Chunk.empty
        )
    private val _probeMetadata: Tasty.Java.Metadata =
        Tasty.Java.Metadata(
            throwsTypes = Chunk.empty,
            annotations = Chunk.empty,
            enclosingMethod = Maybe.Absent,
            accessFlags = 0,
            recordComponents = Chunk.empty,
            bootstrapMethods = Chunk.empty,
            nestHost = Maybe.Absent,
            nestMembers = Chunk.empty,
            paramNames = Chunk.empty,
            runtimeTypeAnnotations = Chunk.empty
        )
    private val _probeDescriptor: Tasty.Java.Module.Descriptor =
        Tasty.Java.Module.Descriptor("m", Maybe.Absent, Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty, Chunk.empty)
    private val _probeRecordComponent: Tasty.Java.RecordComponent =
        Tasty.Java.RecordComponent(Tasty.Name("x"), Tasty.Type.Named(SymbolId(0)))

    "Leaf 1: pathsResolveUnderTastyJava -- compile probe passes when file compiles" in {
        // The compile-time probes above are sufficient; this leaf just records that they compiled.
        discard(_probeAnnotation)
        discard(_probeMetadata)
        discard(_probeDescriptor)
        discard(_probeRecordComponent)
        succeed
    }

    // ── Leaf 2: legacyPathsNoLongerResolve ───────────────────────────────────
    // Given: a compileErrors probe for the old kyo.Tasty.JavaAnnotation path.
    // When: the probe is evaluated at compile time via compiletime.testing.typeCheckErrors.
    // Then: the returned list is non-empty (the old name was removed by Cat 9).
    "Leaf 2: legacyPathsNoLongerResolve -- kyo.Tasty.JavaAnnotation does not resolve (removed from public API)" in {
        val errs = compiletime.testing.typeCheckErrors("val a: kyo.Tasty.JavaAnnotation = ???")
        assert(errs.nonEmpty, "kyo.Tasty.JavaAnnotation must not resolve after Cat 9 namespace move")
        succeed
    }

    // ── Leaf 3: recordComponentSchemaRoundTrip ───────────────────────────────
    // Given: a Java.RecordComponent fixture.
    // When: encoded via Schema then decoded.
    // Then: decoded value equals the original.
    "Leaf 3: recordComponentSchemaRoundTrip -- Java.RecordComponent round-trips via Schema" in {
        val v       = Tasty.Java.RecordComponent(Tasty.Name("x"), Tasty.Type.Named(SymbolId(0)))
        val encoded = Json.encode(v)
        val decoded = Json.decode[Tasty.Java.RecordComponent](encoded)
        decoded match
            case Result.Success(out) => assert(out == v, s"round-trip mismatch: $out != $v")
            case Result.Failure(e)   => fail(s"decode failed: $e")
            case Result.Panic(t)     => throw t
        end match
        succeed
    }

    // ── Leaf 4: paramGroupSchemaRoundTrip ────────────────────────────────────
    // Given: a Java.ParamGroup fixture with three parameter names.
    // When: encoded via Schema then decoded.
    // Then: decoded value equals the original; simple-name list byte-stable.
    "Leaf 4: paramGroupSchemaRoundTrip -- Java.ParamGroup round-trips via Schema" in {
        val v = Tasty.Java.ParamGroup(
            Tasty.Name("m"),
            Chunk(Tasty.Name("a"), Tasty.Name("b"), Tasty.Name("c"))
        )
        val encoded = Json.encode(v)
        Json.decode[Tasty.Java.ParamGroup](encoded) match
            case Result.Success(decoded) => assert(decoded == v, s"round-trip failed: $decoded != $v")
            case Result.Failure(e)       => fail(s"decode failed: $e")
            case Result.Panic(t)         => throw t
        end match
        succeed
    }

    // ── Leaf 5: enclosingMethodSchemaRoundTripUsesSchemaSymbol ───────────────
    // Given: a Java.EnclosingMethod fixture using a Symbol.Package as owner.
    // When: encoded via Schema[Java.EnclosingMethod] then decoded.
    // Then: decoded value equals the original; confirms schemaSymbol is in scope at the derive site.
    "Leaf 5: enclosingMethodSchemaRoundTripUsesSchemaSymbol -- Java.EnclosingMethod round-trips via Schema" in {
        val sym = Tasty.Symbol.Package(
            SymbolId(42),
            Tasty.Name("pkg"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Chunk.empty
        )
        val v       = Tasty.Java.EnclosingMethod(sym, Tasty.Name("init"))
        val encoded = Json.encode(v)
        Json.decode[Tasty.Java.EnclosingMethod](encoded) match
            case Result.Success(decoded) => assert(decoded == v, s"round-trip failed: $decoded != $v")
            case Result.Failure(e)       => fail(s"decode failed: $e")
            case Result.Panic(t)         => throw t
        end match
        succeed
    }

    // ── Leaf 6: moduleSubRecordsSchemaRoundTrip ──────────────────────────────
    // Given: fixtures for Java.Module.Requires, Exports, Opens, Provides.
    // When: each is encoded via Schema and decoded.
    // Then: every decoded value equals its original.
    "Leaf 6: moduleSubRecordsSchemaRoundTrip -- Java.Module sub-records round-trip via Schema" in {
        val req  = Tasty.Java.Module.Requires("kyo.tasty", Maybe.Present("1.0"), isTransitive = true, isStaticPhase = false)
        val exp  = Tasty.Java.Module.Exports("kyo", Chunk.empty, 0L)
        val opn  = Tasty.Java.Module.Opens("kyo.internal", Chunk("foo.bar"), 0L)
        val prov = Tasty.Java.Module.Provides("kyo.tasty.service.X", Chunk("kyo.tasty.impl.X"))

        def roundTrip[T](v: T, label: String)(using Schema[T], CanEqual[T, T]): Unit =
            val encoded = Json.encode(v)
            Json.decode[T](encoded) match
                case Result.Success(decoded) => assert(decoded == v, s"$label round-trip mismatch: $decoded != $v"): Unit
                case Result.Failure(e)       => fail(s"$label decode failed: $e")
                case Result.Panic(t)         => throw t
            end match
        end roundTrip

        roundTrip(req, "Requires")
        roundTrip(exp, "Exports")
        roundTrip(opn, "Opens")
        roundTrip(prov, "Provides")
        succeed
    }

    // ── Leaf 7: annotationMutualRecursionWorkaroundUnchangedSemantics ─────────
    // Given: a Java.Annotation whose values contains AnnotationVal(nested = Java.Annotation(...)).
    // When: encoded via schemaAnnotation and decoded.
    // Then: decoded value equals the original; confirms the null.asInstanceOf workaround is retained.
    "Leaf 7: annotationMutualRecursionWorkaroundUnchangedSemantics -- Java.Annotation recursive round-trip" in {
        val nested = Tasty.Java.Annotation(
            Tasty.Symbol.Package(SymbolId(1), Tasty.Name("ann"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty),
            Chunk.empty
        )
        val ann = Tasty.Java.Annotation(
            Tasty.Symbol.Package(SymbolId(0), Tasty.Name("outer"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty),
            Chunk((Tasty.Name("value"), Tasty.Java.Annotation.Value.AnnotationVal(nested)))
        )
        val encoded = Json.encode(ann)
        Json.decode[Tasty.Java.Annotation](encoded) match
            case Result.Success(decoded) => assert(decoded == ann, s"mutual-recursion round-trip mismatch: $decoded != $ann")
            case Result.Failure(e)       => fail(s"mutual-recursion decode failed: $e")
            case Result.Panic(t)         => throw t
        end match
        succeed
    }

end TastyJavaNamespaceTest
