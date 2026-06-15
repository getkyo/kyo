package kyo

import kyo.Json
import kyo.Tasty.SymbolId

/** Tests for the Tasty.Java namespace and Schema derivations.
  *
  * Covers: compile-time probe for the new namespace, old-path absence, and Schema round-trips
  * for Java.RecordComponent, ParamGroup, EnclosingMethod, Module sub-records, and Annotation.
  */
class TastyJavaNamespaceTest extends kyo.test.Test[Any]:

    // These are compile-time probes: if any type is missing, the file fails to compile.
    private val _probeAnnotation: Tasty.Java.Annotation =
        Tasty.Java.Annotation(
            Tasty.Symbol.Package(SymbolId(0), Tasty.Name("p"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty),
            Chunk.empty,
            Tasty.Name("p")
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

    "pathsResolveUnderTastyJava -- compile probe passes when file compiles" in {
        // The compile-time probes above are sufficient; this leaf just records that they compiled.
        discard(_probeAnnotation)
        discard(_probeMetadata)
        discard(_probeDescriptor)
        discard(_probeRecordComponent)
        succeed
    }

    "legacyPathsNoLongerResolve -- kyo.Tasty.JavaAnnotation does not resolve (removed from public API)" in {
        val errs = compiletime.testing.typeCheckErrors("val a: kyo.Tasty.JavaAnnotation = ???")
        assert(errs.nonEmpty, "kyo.Tasty.JavaAnnotation must not resolve")
        succeed
    }

    "recordComponentSchemaRoundTrip -- Java.RecordComponent round-trips via Schema" in {
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

    "paramGroupSchemaRoundTrip -- Java.ParamGroup round-trips via Schema" in {
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

    "enclosingMethodSchemaRoundTripUsesSchemaSymbol -- Java.EnclosingMethod round-trips via Schema" in {
        val symbol = Tasty.Symbol.Package(
            SymbolId(42),
            Tasty.Name("pkg"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Chunk.empty
        )
        val v       = Tasty.Java.EnclosingMethod(symbol, Tasty.Name("init"))
        val encoded = Json.encode(v)
        Json.decode[Tasty.Java.EnclosingMethod](encoded) match
            case Result.Success(decoded) => assert(decoded == v, s"round-trip failed: $decoded != $v")
            case Result.Failure(e)       => fail(s"decode failed: $e")
            case Result.Panic(t)         => throw t
        end match
        succeed
    }

    "moduleSubRecordsSchemaRoundTrip -- Java.Module sub-records round-trip via Schema" in {
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

    "annotationMutualRecursionWorkaroundUnchangedSemantics -- Java.Annotation recursive round-trip" in {
        val nested = Tasty.Java.Annotation(
            Tasty.Symbol.Package(SymbolId(1), Tasty.Name("annotation"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty),
            Chunk.empty,
            Tasty.Name("annotation")
        )
        val annotation = Tasty.Java.Annotation(
            Tasty.Symbol.Package(SymbolId(0), Tasty.Name("outer"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty),
            Chunk((Tasty.Name("value"), Tasty.Java.Annotation.Value.AnnotationVal(nested))),
            Tasty.Name("outer")
        )
        val encoded = Json.encode(annotation)
        Json.decode[Tasty.Java.Annotation](encoded) match
            case Result.Success(decoded) => assert(decoded == annotation, s"mutual-recursion round-trip mismatch: $decoded != $annotation")
            case Result.Failure(e)       => fail(s"mutual-recursion decode failed: $e")
            case Result.Panic(t)         => throw t
        end match
        succeed
    }

end TastyJavaNamespaceTest
