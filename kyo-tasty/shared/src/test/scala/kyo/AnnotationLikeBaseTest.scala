package kyo

import kyo.Tasty.SymbolId

/** Tests for the sealed Tasty.AnnotationLike base and the annotationFullName field on both subtypes.
  *
  * Covers namespace discipline, subtype assignability, exhaustive pattern matching,
  * annotationFullName field correctness, findAnnotation return-type contract, and snapshot
  * round-trip consistency.
  */
class AnnotationLikeBaseTest extends kyo.test.Test[Any]:

    // kyo.Tasty.AnnotationLike resolves; kyo.Tasty.Java.AnnotationLike does not.
    "namespace-discipline: AnnotationLike at Tasty.AnnotationLike; no Java.AnnotationLike" in {
        val presentErrors = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.AnnotationLike = ???")
        val absentErrors  = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.Java.AnnotationLike = ???")
        assert(presentErrors.isEmpty, s"kyo.Tasty.AnnotationLike must resolve; got: $presentErrors")
        assert(absentErrors.nonEmpty, "kyo.Tasty.Java.AnnotationLike must not resolve")
        succeed
    }

    // Tasty.Annotation extends AnnotationLike.
    "Annotation extends AnnotationLike: assignable to sealed base" in {
        val annotation: Tasty.Annotation = Tasty.Annotation(Tasty.Type.Named(SymbolId(-1)), Chunk.empty, Tasty.Name(""))
        val base: Tasty.AnnotationLike   = annotation
        assert(base.annotationFullName == Tasty.Name(""), "annotationFullName accessible via sealed base")
        succeed
    }

    // Tasty.Java.Annotation extends AnnotationLike.
    "Java.Annotation extends AnnotationLike: assignable to sealed base" in {
        val pkg                         = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("p"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        val jann: Tasty.Java.Annotation = Tasty.Java.Annotation(pkg, Chunk.empty, Tasty.Name("p"))
        val base: Tasty.AnnotationLike  = jann
        assert(base.annotationFullName == Tasty.Name("p"), "annotationFullName accessible via sealed base")
        succeed
    }

    // annotationFullName constructor field on Tasty.Annotation reflects the value passed.
    "Annotation.annotationFullName holds the constructor value" in {
        val annotation = Tasty.Annotation(
            Tasty.Type.Named(SymbolId(-1)),
            Chunk.empty,
            Tasty.Name("scala.deprecated")
        )
        assert(
            annotation.annotationFullName == Tasty.Name("scala.deprecated"),
            s"Expected 'scala.deprecated' but got ${annotation.annotationFullName}"
        )
        succeed
    }

    // annotationFullName constructor field on Tasty.Java.Annotation reflects the value passed.
    "Java.Annotation.annotationFullName holds the constructor value" in {
        val pkg  = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("p"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        val jann = Tasty.Java.Annotation(pkg, Chunk.empty, Tasty.Name("java.lang.Override"))
        assert(
            jann.annotationFullName == Tasty.Name("java.lang.Override"),
            s"Expected 'java.lang.Override' but got ${jann.annotationFullName}"
        )
        succeed
    }

    // Pattern match over AnnotationLike maps both concrete subtypes. The defensive catch-all is required only
    // because this file also calls `compiletime.testing.typeCheckErrors`, which injects a phantom anonymous
    // AnnotationLike subtype into this compilation unit's exhaustivity analysis; it is unreachable at runtime.
    "pattern-match over AnnotationLike maps both concrete subtypes" in {
        val pkg                        = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name("p"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        val base: Tasty.AnnotationLike = Tasty.Java.Annotation(pkg, Chunk.empty, Tasty.Name("p"))
        val result: String = base match
            case _: Tasty.Annotation      => "scala"
            case _: Tasty.Java.Annotation => "java"
            case other                    => fail(s"unexpected AnnotationLike subtype: $other")
        assert(result == "java")
        succeed
    }

    // AnnotationLike is sealed; extending it from outside the hierarchy produces a compile error.
    // This verifies the sealed property that guarantees exhaustive match coverage is finite and known.
    "AnnotationLike is sealed: only Annotation and Java.Annotation exist; extending from outside the hierarchy fails to compile" in {
        val errors = compiletime.testing.typeCheckErrors(
            "class ExternalAnnotation extends kyo.Tasty.AnnotationLike { def annotationFullName = kyo.Tasty.Name(\"\") }"
        )
        assert(errors.nonEmpty, "AnnotationLike must be sealed; extending it from outside Tasty must fail to compile")
        succeed
    }

    // Tasty.Annotation snapshot round-trip preserves all three fields.
    "Annotation snapshot round-trip preserves annotationFullName" in {
        import kyo.Schema
        val annotation = Tasty.Annotation(Tasty.Type.Named(SymbolId(-1)), Chunk.empty, Tasty.Name("scala.deprecated"))
        val encoded    = Json.encode(annotation)
        Json.decode[Tasty.Annotation](encoded) match
            case Result.Success(decoded) =>
                assert(decoded == annotation, s"Round-trip mismatch: $decoded != $annotation")
                assert(
                    decoded.annotationFullName == Tasty.Name("scala.deprecated"),
                    s"annotationFullName not preserved: ${decoded.annotationFullName}"
                )
                succeed
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t
        end match
    }

    // Tasty.Java.Annotation snapshot round-trip preserves all three fields.
    "Java.Annotation snapshot round-trip preserves annotationFullName" in {
        val pkg     = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("p"), Tasty.Flags.empty, SymbolId(1), Chunk.empty)
        val jann    = Tasty.Java.Annotation(pkg, Chunk.empty, Tasty.Name("java.lang.Override"))
        val encoded = Json.encode(jann)
        Json.decode[Tasty.Java.Annotation](encoded) match
            case Result.Success(decoded) =>
                assert(decoded == jann, s"Round-trip mismatch: $decoded != $jann")
                assert(
                    decoded.annotationFullName == Tasty.Name("java.lang.Override"),
                    s"annotationFullName not preserved: ${decoded.annotationFullName}"
                )
                succeed
            case Result.Failure(e) => fail(s"Decode failed: $e")
            case Result.Panic(t)   => throw t
        end match
    }

    // classpath.findAnnotation return type is the pure Maybe[AnnotationLike].
    "classpath.findAnnotation return type is Maybe[AnnotationLike]" in {
        val pkg = Tasty.Symbol.Package(SymbolId(-1), Tasty.Name(""), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        val _: Maybe[Tasty.AnnotationLike] = Tasty.Classpath.empty.findAnnotation(pkg, "")
        succeed
    }

    // findAnnotation returns Present(Annotation) with correct annotationFullName via classpath fixture.
    "findAnnotation on Scala-annotated method returns Present(Annotation) with annotationFullName" in {
        // Build a minimal classpath: pkg "scala"(0), class "deprecated"(1), method "m"(2) with @deprecated.
        val scalaPkg = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("scala"), Tasty.Flags.empty, SymbolId(-1), Chunk(SymbolId(1)))
        val deprecatedCls = Tasty.Symbol.Class(
            SymbolId(1),
            Tasty.Name("deprecated"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        val annotation = Tasty.Annotation(
            Tasty.Type.Named(SymbolId(1)),
            Chunk.empty,
            Tasty.Name("scala.deprecated")
        )
        val method = Tasty.Symbol.Method(
            SymbolId(2),
            Tasty.Name("m"),
            Tasty.Flags.empty,
            SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk(annotation),
            Maybe.Absent
        )
        val symbols = Chunk(scalaPkg, deprecatedCls, method)
        Tasty.Classpath.fromPicklesWithSymbols(symbols).map { classpath =>
            classpath.findAnnotation(method, "scala.deprecated") match
                case Maybe.Present(a: Tasty.Annotation) =>
                    assert(
                        a.annotationFullName == Tasty.Name("scala.deprecated"),
                        s"Expected 'scala.deprecated' but got ${a.annotationFullName}"
                    )
                case other =>
                    fail(s"Expected Present(Tasty.Annotation) but got $other")
        }
    }

    // annotationFullName on a loaded Annotation is consistent with typeFullNameString.
    "Annotation.annotationFullName is consistent with the type fully-qualified name" in {
        val scalaPkg = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("scala"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        val deprecatedCls = Tasty.Symbol.Class(
            SymbolId(1),
            Tasty.Name("deprecated"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        val annotation = Tasty.Annotation(
            Tasty.Type.Named(SymbolId(1)),
            Chunk.empty,
            Tasty.Name("scala.deprecated")
        )
        val symbols = Chunk(scalaPkg, deprecatedCls)
        Tasty.Classpath.fromPicklesWithSymbols(symbols).map { classpath =>
            import kyo.Tasty.Name.asString
            import AllowUnsafe.embrace.danger
            val computedFullName = classpath.typeFullNameString(annotation.annotationType)
            assert(
                annotation.annotationFullName.asString == computedFullName,
                s"annotationFullName '${annotation.annotationFullName.asString}' != typeFullNameString '$computedFullName'"
            )
            succeed
        }
    }

    // Java.Annotation.annotationFullName is consistent with computeFullName on annotationClass.
    "Java.Annotation.annotationFullName is consistent with computeFullName on annotationClass" in {
        val jlPkg   = Tasty.Symbol.Package(SymbolId(0), Tasty.Name("java"), Tasty.Flags.empty, SymbolId(-1), Chunk.empty)
        val langPkg = Tasty.Symbol.Package(SymbolId(1), Tasty.Name("lang"), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val overrideCls = Tasty.Symbol.Class(
            SymbolId(2),
            Tasty.Name("Override"),
            Tasty.Flags.empty,
            SymbolId(1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        val jann    = Tasty.Java.Annotation(overrideCls, Chunk.empty, Tasty.Name("java.lang.Override"))
        val symbols = Chunk(jlPkg, langPkg, overrideCls)
        Tasty.Classpath.fromPicklesWithSymbols(symbols).map { classpath =>
            import kyo.Tasty.Name.asString
            import AllowUnsafe.embrace.danger
            val computedFullName = classpath.computeFullName(jann.annotationClass).asString
            assert(
                jann.annotationFullName.asString == computedFullName,
                s"annotationFullName '${jann.annotationFullName.asString}' != computeFullName '$computedFullName'"
            )
            succeed
        }
    }

end AnnotationLikeBaseTest
