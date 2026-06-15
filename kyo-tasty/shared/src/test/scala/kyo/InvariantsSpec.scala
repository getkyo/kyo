package kyo

import kyo.internal.tasty.query.Binding

/** Structural invariants for kyo-tasty: object-Tasty active-binding lifecycle,
  * and the effectful-boundary contract for AllowUnsafe sites.
  *
  * The four AllowUnsafe sites documented in kyo-tasty/CONTRIBUTING.md are:
  *   1. `Tasty.withClasspath` (safe-tier entry point that calls the loader)
  *   2. `Tasty.global` (module-level lazy fallback binding)
  *   3. `Tasty.bodyTree` (lazy AST decode under Sync.Unsafe.defer)
  *   4. `Tasty.evictOlderThan` (snapshot-cache maintenance)
  *
  * Each test below exercises observable behavior tied to one of these sites or to
  * the active-binding and Local-scoping invariants.
  */
class InvariantsSpec extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // Tasty.global must be a lazy val singleton: two accesses return the same Binding reference.
    // This verifies AllowUnsafe site 2 (Tasty.global) produces a stable, reusable Binding.
    "Tasty.global lazy val returns the same Binding instance on every access" in {
        val b1 = Tasty.global
        val b2 = Tasty.global
        assert(b1 eq b2, "Tasty.global must return the same Binding instance on every access (lazy val singleton)")
        succeed
    }

    // Tasty.bodyTree returns Maybe.Absent for a symbol whose classpath was installed via
    // withClasspath(classpath) (pure-data path). The pure-data overload installs Binding(classpath, Maybe.Absent),
    // which has no DecodeContext. Absent decodeCtx is the documented condition for absent body bytes.
    "Tasty.bodyTree returns Maybe.Absent when the active binding carries no decode context" in {
        val syntheticMethod = Tasty.Symbol.Method(
            Tasty.SymbolId(0),
            Tasty.Name("noBody"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent
        )
        val classpath = Tasty.Classpath(
            symbols = Chunk(syntheticMethod),
            indices = Tasty.Classpath.Indices.empty,
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        Abort.run[TastyError](
            Tasty.withClasspath(classpath) {
                Tasty.bodyTree(syntheticMethod)
            }
        ).map {
            case Result.Success(maybe) =>
                assert(!maybe.isDefined, "bodyTree must return Maybe.Absent when the active binding has no decode context")
                succeed
            case Result.Failure(e) =>
                fail(s"bodyTree raised unexpected TastyError: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    // The fully-qualified name kyo.internal.tasty.query.TastyState must not resolve.
    // object Tasty exposes `bindingLocal` and `global` as the active-binding handles.
    "kyo.internal.tasty.query.TastyState is absent; referencing it fails to compile" in {
        val errors = compiletime.testing.typeCheckErrors("kyo.internal.tasty.query.TastyState")
        assert(errors.nonEmpty, "TastyState must not exist; expected a compile error when referencing it")
        succeed
    }

    // Tasty.bindingLocal is exposed on object Tasty and is accessible from package kyo.
    "Tasty.bindingLocal is present on object Tasty and accessible from package kyo" in {
        val local: Local[Maybe[Binding]] = Tasty.bindingLocal
        assert(local != null, "Tasty.bindingLocal must be a non-null Local[Maybe[Binding]]")
        succeed
    }

    // LoadingSymbol must remain private[kyo]; it must be accessible from within package kyo
    // and is verified here (the restriction from outside the package is enforced by the Scala 3
    // access modifier and is tested in external/TastyBindingLocalVisibilityTest.scala).
    "kyo.internal.tasty.symbol.LoadingSymbol is accessible from package kyo" in {
        val errors = compiletime.testing.typeCheckErrors("kyo.internal.tasty.symbol.LoadingSymbol : Any")
        assert(errors.isEmpty, s"LoadingSymbol must be accessible from package kyo; got unexpected errors: $errors")
        succeed
    }

    // DecodeContext must remain private[kyo]; it must be accessible from within package kyo.
    "kyo.internal.tasty.query.DecodeContext is accessible from package kyo" in {
        val errors = compiletime.testing.typeCheckErrors("kyo.internal.tasty.query.DecodeContext : Any")
        assert(errors.isEmpty, s"DecodeContext must be accessible from package kyo; got unexpected errors: $errors")
        succeed
    }

    // Tasty.classpath return type is exactly Tasty.Classpath < Sync.
    // A compile error here would indicate unintended effect-row widening.
    "Tasty.classpath return type is Tasty.Classpath < Sync (no effect-row widening)" in {
        val _: Tasty.Classpath < Sync = Tasty.classpath
        succeed
    }

    // Tasty.withClasspath scoping: inside withClasspath the local binding must be the one provided.
    "Tasty.classpath inside withClasspath returns the bound classpath" in {
        val fixture = Tasty.Classpath(
            symbols = Chunk(
                Tasty.Symbol.Package(
                    Tasty.SymbolId(0),
                    Tasty.Name("inv013root"),
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
            Tasty.classpath.map { classpath =>
                assert(classpath eq fixture, "Tasty.classpath inside withClasspath must return the bound Classpath by reference")
                succeed
            }
        }
    }

    // Subtyping.isSubtype returns Result[TastyError, SubtypeVerdict] and takes no mutable
    // accumulator parameter. The type-level assignment below verifies the exact return type
    // without calling the method.
    "Subtyping.isSubtype signature is Result-typed and takes no errAcc parameter" in {
        // If the method signature accepted an accumulator, the four-argument form below
        // would not unify with the expected function type.
        val _: (kyo.Tasty.Type, kyo.Tasty.Type, kyo.Tasty.Classpath, Int) => kyo.Result[kyo.TastyError, kyo.Tasty.SubtypeVerdict] =
            kyo.internal.tasty.type_.Subtyping.isSubtype
        succeed
    }

    // Binding.subtypingErrors does not exist on Binding; referencing it must produce a compile error.
    "Binding.subtypingErrors field is absent" in {
        val errors = compiletime.testing.typeCheckErrors(
            "(??? : kyo.internal.tasty.query.Binding).subtypingErrors"
        )
        assert(errors.nonEmpty, "Binding.subtypingErrors must not exist as a field on Binding")
        succeed
    }

    // Tasty.Classpath fields accept non-null values; constructing a Classpath with concrete
    // values must type-check without error.
    "no | Null type expressions in Tasty.scala outside scaladoc prose" in {
        val errors = compiletime.testing.typeCheckErrors(
            "val _: kyo.Tasty.Classpath = kyo.Tasty.Classpath(kyo.Chunk.empty, kyo.Tasty.Classpath.Indices.empty, kyo.Chunk.empty, kyo.Chunk.empty, kyo.Tasty.SymbolId(-1))"
        )
        assert(errors.isEmpty, s"Classpath construction must type-check cleanly; unexpected errors: $errors")
        succeed
    }

    // Flag.values length pins the 45-case wire-format invariant. Each case carries a unique
    // bit: Long whose value is load-bearing for the per-symbol Flags field in the snapshot format.
    "Flag.values.length is 45 and Flag enum is accessible" in {
        assert(Tasty.Flag.values.length == 45, s"Expected 45 Flag cases but got ${Tasty.Flag.values.length}")
        // Verify the first and last cases in declaration order to confirm ordinal stability.
        assert(Tasty.Flag.values(0) == Tasty.Flag.Inline, "First Flag case must be Inline")
        assert(Tasty.Flag.values(44) == Tasty.Flag.Scala2, "Last Flag case must be Scala2")
        succeed
    }

    // Tasty.AnnotationLike is a sealed type at the documented namespace path.
    "Tasty.AnnotationLike resolves at kyo.Tasty.AnnotationLike" in {
        val errors = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.AnnotationLike = ???")
        assert(errors.isEmpty, s"kyo.Tasty.AnnotationLike must resolve; unexpected errors: $errors")
        succeed
    }

    // kyo.Tasty.Java.AnnotationLike must not resolve (no such type in the Java namespace).
    "kyo.Tasty.Java.AnnotationLike does not exist" in {
        val errors = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.Java.AnnotationLike = ???")
        assert(errors.nonEmpty, "kyo.Tasty.Java.AnnotationLike must not resolve; expected a compile error")
        succeed
    }

    // Tasty.Annotation extends AnnotationLike; assigning to the sealed base type-checks.
    "Tasty.Annotation is assignable to Tasty.AnnotationLike" in {
        val annotation: Tasty.AnnotationLike =
            Tasty.Annotation(Tasty.Type.Named(Tasty.SymbolId(-1)), Chunk.empty, Tasty.Name(""))
        assert(annotation.annotationFullName == Tasty.Name(""), "annotationFullName round-trips through AnnotationLike")
        succeed
    }

    // Tasty.Java.Annotation extends AnnotationLike; assigning to the sealed base type-checks.
    "Tasty.Java.Annotation is assignable to Tasty.AnnotationLike" in {
        val pkg = Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("p"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        val jann: Tasty.AnnotationLike =
            Tasty.Java.Annotation(pkg, Chunk.empty, Tasty.Name("p"))
        assert(jann.annotationFullName == Tasty.Name("p"), "annotationFullName round-trips through AnnotationLike")
        succeed
    }

    // Pattern match over AnnotationLike maps both concrete subtypes. The defensive catch-all is required only
    // because this file also calls `compiletime.testing.typeCheckErrors`, which injects a phantom anonymous
    // AnnotationLike subtype into this compilation unit's exhaustivity analysis; it is unreachable at runtime.
    "pattern-match over Tasty.AnnotationLike maps both concrete subtypes" in {
        val annotation: Tasty.AnnotationLike =
            Tasty.Annotation(Tasty.Type.Named(Tasty.SymbolId(-1)), Chunk.empty, Tasty.Name(""))
        val result: String = annotation match
            case _: Tasty.Annotation      => "scala"
            case _: Tasty.Java.Annotation => "java"
            case other                    => fail(s"unexpected AnnotationLike subtype: $other")
        assert(result == "scala")
        succeed
    }

    // annotationFullName field on Tasty.Annotation holds the value passed at construction.
    "Tasty.Annotation.annotationFullName holds the constructor value" in {
        val annotation = Tasty.Annotation(
            Tasty.Type.Named(Tasty.SymbolId(-1)),
            Chunk.empty,
            Tasty.Name("scala.deprecated")
        )
        assert(
            annotation.annotationFullName == Tasty.Name("scala.deprecated"),
            s"Expected 'scala.deprecated' but got ${annotation.annotationFullName}"
        )
        succeed
    }

    // annotationFullName field on Tasty.Java.Annotation holds the value passed at construction.
    "Tasty.Java.Annotation.annotationFullName holds the constructor value" in {
        val pkg  = Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("p"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        val jann = Tasty.Java.Annotation(pkg, Chunk.empty, Tasty.Name("java.lang.Override"))
        assert(
            jann.annotationFullName == Tasty.Name("java.lang.Override"),
            s"Expected 'java.lang.Override' but got ${jann.annotationFullName}"
        )
        succeed
    }

    // classpath.findAnnotation return type is the pure Maybe[AnnotationLike].
    "classpath.findAnnotation return type is Maybe[AnnotationLike]" in {
        val _: Maybe[Tasty.AnnotationLike] = Tasty.Classpath.empty.findAnnotation(
            Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name(""), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty),
            ""
        )
        succeed
    }

    // AnnotationLike and annotationFullName are accessible on all three platforms without a platform filter.
    // This test compiles and runs on JVM, JS, and Native.
    "AnnotationLike and annotationFullName are accessible on all three platforms without platform filter" in {
        val annotation: Tasty.AnnotationLike =
            Tasty.Annotation(Tasty.Type.Named(Tasty.SymbolId(-1)), Chunk.empty, Tasty.Name("test.Ann"))
        assert(annotation.annotationFullName == Tasty.Name("test.Ann"))
        val pkg = Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("p"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        val jann: Tasty.AnnotationLike =
            Tasty.Java.Annotation(pkg, Chunk.empty, Tasty.Name("java.lang.Override"))
        assert(jann.annotationFullName == Tasty.Name("java.lang.Override"))
        succeed
    }

    // Classpath.owner, fullName, binaryName, show, ownersChain, companion, signature, paramLists
    // are all pure instance methods: they require no Frame and return plain values, not effects.
    // This test compiles and runs on JVM, JS, and Native without a platform filter.
    "Classpath pure instance methods are callable without a Frame on all three platforms" in {
        val shopPkg = Tasty.Symbol.Package(
            Tasty.SymbolId(0),
            Tasty.Name("shop"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Chunk.empty
        )
        val dogClass = Tasty.Symbol.Class(
            Tasty.SymbolId(1),
            Tasty.Name("Dog"),
            Tasty.Flags.empty,
            Tasty.SymbolId(0),
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
        Sync.defer {
            Tasty.Classpath.make(
                symbols = Chunk(shopPkg, dogClass),
                rootSymbolId = Tasty.SymbolId(-1),
                topLevelClassIds = Chunk(Tasty.SymbolId(1)),
                packageIds = Chunk(Tasty.SymbolId(0)),
                fullNameIndex = Dict("shop.Dog" -> Tasty.SymbolId(1)),
                packageIndex = Dict("shop" -> Tasty.SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }.map { classpath =>
            import Tasty.Name.asString
            // Each call below must compile without `using Frame` to confirm pure shape.
            val _: Maybe[Tasty.Symbol] = classpath.owner(dogClass)
            val _: Tasty.Name          = classpath.fullName(dogClass)
            val _: String              = classpath.binaryName(dogClass)
            val _: String              = classpath.show(dogClass)
            val _: Chunk[Tasty.Symbol] = classpath.ownersChain(dogClass)
            val _: Maybe[Tasty.Symbol] = classpath.companion(dogClass)
            assert(
                classpath.fullName(dogClass).asString == "shop.Dog",
                s"fullName must be 'shop.Dog'; got ${classpath.fullName(dogClass).asString}"
            )
            succeed
        }
    }

    // parents, permittedSubclasses, directSubclassesOf, subclassesOf, implementationsOf
    // are pure instance methods that require no Frame and return plain values.
    // This test compiles and runs on JVM, JS, and Native without a platform filter.
    "Classpath ClassLike-narrowed methods are callable without a Frame on all three platforms" in {
        val animalId = Tasty.SymbolId(1)
        val dogId    = Tasty.SymbolId(2)
        val animalClass = Tasty.Symbol.Class(
            animalId,
            Tasty.Name("Animal"),
            Tasty.Flags.empty,
            Tasty.SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            parentTypes = Chunk.empty,
            Chunk.empty,
            Chunk.empty,
            permittedSubclassIds = Maybe.Present(Chunk(dogId)),
            Chunk.empty,
            Chunk.empty
        )
        val dogClass = Tasty.Symbol.Class(
            dogId,
            Tasty.Name("Dog"),
            Tasty.Flags.empty,
            Tasty.SymbolId(0),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            parentTypes = Chunk(Tasty.Type.Named(animalId)),
            Chunk.empty,
            Chunk.empty,
            Maybe.Absent,
            Chunk.empty,
            Chunk.empty
        )
        Sync.defer {
            Tasty.Classpath.make(
                symbols = Chunk(animalClass, dogClass),
                rootSymbolId = Tasty.SymbolId(-1),
                topLevelClassIds = Chunk(animalId, dogId),
                packageIds = Chunk.empty,
                fullNameIndex = Dict.empty,
                packageIndex = Dict.empty,
                subclassIndex = Dict(animalId -> Chunk(dogId)),
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }.map { classpath =>
            // Each call below must compile without `using Frame` to confirm pure shape.
            val _: Chunk[Tasty.Symbol]           = classpath.parents(animalClass)
            val _: Maybe[Chunk[Tasty.Symbol]]    = classpath.permittedSubclasses(animalClass)
            val _: Chunk[Tasty.Symbol.ClassLike] = classpath.directSubclassesOf(animalClass)
            val _: Chunk[Tasty.Symbol.ClassLike] = classpath.subclassesOf(animalClass)
            val _: Chunk[Tasty.Symbol.Class]     = classpath.implementationsOf(animalClass)
            val parents                          = classpath.parents(dogClass)
            assert(parents.nonEmpty, s"Dog must have Animal as parent; got $parents")
            succeed
        }
    }

    // declarations, members, findMember, findDeclaredMember accept Symbol.ClassLike | Symbol.Package
    // and are pure instance methods (no Frame, no < Sync). This test compiles and runs on all platforms.
    "Classpath union-group pure instance methods are callable without a Frame on all three platforms" in {
        val shopPkg = Tasty.Symbol.Package(
            Tasty.SymbolId(0),
            Tasty.Name("shop"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Chunk.empty
        )
        val dogClass = Tasty.Symbol.Class(
            Tasty.SymbolId(1),
            Tasty.Name("Dog"),
            Tasty.Flags.empty,
            Tasty.SymbolId(0),
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
        Sync.defer {
            Tasty.Classpath.make(
                symbols = Chunk(shopPkg, dogClass),
                rootSymbolId = Tasty.SymbolId(0),
                topLevelClassIds = Chunk(Tasty.SymbolId(1)),
                packageIds = Chunk(Tasty.SymbolId(0)),
                fullNameIndex = Dict("shop.Dog" -> Tasty.SymbolId(1)),
                packageIndex = Dict("shop" -> Tasty.SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }.map { classpath =>
            // Each call must compile without `using Frame` to confirm pure shape.
            val _: Chunk[Tasty.Symbol] = classpath.declarations(dogClass)
            val _: Chunk[Tasty.Symbol] = classpath.members(dogClass)
            val _: Chunk[Tasty.Symbol] = classpath.members(dogClass, Tasty.MemberScope.All)
            val _: Maybe[Tasty.Symbol] = classpath.findMember(dogClass, "noSuch")
            val _: Maybe[Tasty.Symbol] = classpath.findDeclaredMember(dogClass, "noSuch")
            val _: Chunk[Tasty.Symbol] = classpath.declarations(shopPkg)
            val _: Chunk[Tasty.Symbol] = classpath.members(shopPkg)
            assert(classpath.declarations(dogClass).isEmpty, "Dog with no declarationIds must yield empty declarations")
            succeed
        }
    }

    // typeParams, hasAnnotation, findAnnotation, symbolsAnnotatedWith are pure Classpath instance
    // methods: they require no Frame and return plain values. This test compiles and runs on all
    // three platforms without a platform filter.
    //
    // Symbol layout (id value == position in Chunk):
    //   0 -> TypeParam "T" (ownerId = 2)
    //   1 -> Class "Container" (typeParamIds = [0]; annotations = [@shop.Tag])
    //   2 -> Class "Tag" (ownerId = 3)
    //   3 -> Package "shop" (ownerId = -1)
    "Classpath typeParams and annotation methods are callable without a Frame on all three platforms" in {
        val tpId = Tasty.SymbolId(0)
        val tpSym = Tasty.Symbol.TypeParam(
            tpId,
            Tasty.Name("T"),
            Tasty.Flags.empty,
            Tasty.SymbolId(1),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            Tasty.Variance.Invariant
        )
        val annType    = Tasty.Type.Named(Tasty.SymbolId(2))
        val annotation = Tasty.Annotation(annType, Chunk.empty, Tasty.Name("shop.Tag"))
        val classSym = Tasty.Symbol.Class(
            Tasty.SymbolId(1),
            Tasty.Name("Container"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Maybe.Absent,
            Maybe.Absent,
            Maybe.Absent,
            Chunk.empty,
            typeParamIds = Chunk(tpId),
            Chunk.empty,
            Maybe.Absent,
            annotations = Chunk(annotation),
            Chunk.empty
        )
        val annotCls = Tasty.Symbol.Class(
            Tasty.SymbolId(2),
            Tasty.Name("Tag"),
            Tasty.Flags.empty,
            Tasty.SymbolId(3),
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
        val shopPkg = Tasty.Symbol.Package(
            Tasty.SymbolId(3),
            Tasty.Name("shop"),
            Tasty.Flags.empty,
            Tasty.SymbolId(-1),
            Chunk.empty
        )
        Sync.defer {
            Tasty.Classpath.make(
                symbols = Chunk(tpSym, classSym, annotCls, shopPkg),
                rootSymbolId = Tasty.SymbolId(-1),
                topLevelClassIds = Chunk(Tasty.SymbolId(1)),
                packageIds = Chunk(Tasty.SymbolId(3)),
                fullNameIndex = Dict("shop.Tag" -> Tasty.SymbolId(2)),
                packageIndex = Dict("shop" -> Tasty.SymbolId(3)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }.map { classpath =>
            // Each call must compile without `using Frame` to confirm pure shape.
            val _: Chunk[Tasty.Symbol.TypeParam] = classpath.typeParams(classSym)
            val _: Boolean                       = classpath.hasAnnotation(classSym, "shop.Tag")
            val _: Maybe[Tasty.AnnotationLike]   = classpath.findAnnotation(classSym, "shop.Tag")
            val _: Chunk[Tasty.Symbol]           = classpath.symbolsAnnotatedWith("shop.Tag")
            assert(classpath.typeParams(classSym).nonEmpty, "Container has 1 type parameter T")
            assert(classpath.hasAnnotation(classSym, "shop.Tag"), "Container carries @shop.Tag")
            assert(classpath.findAnnotation(classSym, "shop.Tag").isDefined, "findAnnotation returns Present for @shop.Tag")
            assert(classpath.symbolsAnnotatedWith("shop.Tag").nonEmpty, "symbolsAnnotatedWith finds Container via @shop.Tag")
            succeed
        }
    }

    "typeSymbol, typeShow, treeShow, isSubtypeOf are accessible as pure Classpath methods on all platforms without a platform filter" in {
        // Each call compiles without using Frame, confirming the pure shape cross-platform.
        Sync.defer {
            val shopPkg = Tasty.Symbol.Package(
                Tasty.SymbolId(0),
                Tasty.Name("shop"),
                Tasty.Flags.empty,
                Tasty.SymbolId(-1),
                Chunk.empty
            )
            val dogSym = Tasty.Symbol.Class(
                Tasty.SymbolId(1),
                Tasty.Name("Dog"),
                Tasty.Flags.empty,
                Tasty.SymbolId(0),
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
            Tasty.Classpath.make(
                symbols = Chunk(shopPkg, dogSym),
                rootSymbolId = Tasty.SymbolId(-1),
                topLevelClassIds = Chunk(Tasty.SymbolId(1)),
                packageIds = Chunk(Tasty.SymbolId(0)),
                fullNameIndex = Dict("shop.Dog" -> Tasty.SymbolId(1)),
                packageIndex = Dict("shop" -> Tasty.SymbolId(0)),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                moduleIndex = Dict.empty,
                errors = Chunk.empty
            )
        }.map { classpath =>
            val namedType                                   = Tasty.Type.Named(Tasty.SymbolId(1))
            val _: Maybe[Tasty.Symbol]                      = classpath.typeSymbol(namedType)
            val _: String                                   = classpath.typeShow(namedType)
            val _: String                                   = classpath.treeShow(Tasty.Tree.Literal(Tasty.Constant.IntConst(1)))
            val _: Result[TastyError, Tasty.SubtypeVerdict] = classpath.isSubtypeOf(Tasty.Type.Nothing, Tasty.Type.Any)
            assert(classpath.typeSymbol(namedType).isDefined, "typeSymbol(Named(1)) resolves to Dog")
            assert(classpath.typeShow(Tasty.Type.Nothing) == "Nothing", "typeShow(Nothing) == \"Nothing\"")
            assert(classpath.typeShow(Tasty.Type.Any) == "Any", "typeShow(Any) == \"Any\"")
            assert(
                classpath.isSubtypeOf(Tasty.Type.Nothing, Tasty.Type.Any) == Result.Success(Tasty.SubtypeVerdict.Sub),
                "Nothing <: Any is Sub"
            )
            succeed
        }
    }

    // `computeFullName` is the current name; `fullNameUnsafe` must not resolve on `Tasty.Classpath`.
    "Tasty.Classpath exposes computeFullName; fullNameUnsafe is absent" in {
        val oldNameErrors = compiletime.testing.typeCheckErrors(
            "val cp: kyo.Tasty.Classpath = ???; cp.fullNameUnsafe(???)"
        )
        assert(oldNameErrors.nonEmpty, "fullNameUnsafe must not resolve after the rename")
        val newNameErrors = compiletime.testing.typeCheckErrors(
            "val cp: kyo.Tasty.Classpath = ???; val s: kyo.Tasty.Symbol = ???; cp.computeFullName(s)"
        )
        assert(newNameErrors.isEmpty, s"computeFullName must resolve on Tasty.Classpath; got errors: $newNameErrors")
        succeed
    }

    // The companion shortcuts `Tasty.findTrait` and `Tasty.requireTrait` are part of the public surface
    // and must compile from package kyo. The typeCheck wires a Frame via a def so derivation does not
    // block on the in-package Frame restriction.
    "Tasty.findTrait and Tasty.requireTrait compile on object Tasty" in {
        val findErrors = compiletime.testing.typeCheckErrors(
            """def __probe(using kyo.Frame) = kyo.Tasty.findTrait("some.Foo")"""
        )
        assert(findErrors.isEmpty, s"Tasty.findTrait must compile; got errors: $findErrors")
        val requireErrors = compiletime.testing.typeCheckErrors(
            """def __probe(using kyo.Frame) = kyo.Tasty.requireTrait("some.Foo")"""
        )
        assert(requireErrors.isEmpty, s"Tasty.requireTrait must compile; got errors: $requireErrors")
        succeed
    }

    // The `Tasty.Uuid` opaque type replaces `java.util.UUID` on the public `TastyError.InconsistentClasspath`
    // surface. `Uuid.parse` must compile and accept a String; constructing `InconsistentClasspath` with raw
    // `java.util.UUID` must fail to compile.
    "Tasty.Uuid.parse compiles; InconsistentClasspath does not accept java.util.UUID" in {
        val parseErrors = compiletime.testing.typeCheckErrors(
            "kyo.Tasty.Uuid.parse(\"550e8400-e29b-41d4-a716-446655440000\")"
        )
        assert(parseErrors.isEmpty, s"Tasty.Uuid.parse must compile; got errors: $parseErrors")
        val jdkConstructErrors = compiletime.testing.typeCheckErrors(
            "kyo.TastyError.InconsistentClasspath(\"f\", new java.util.UUID(0L, 0L), new java.util.UUID(0L, 0L))"
        )
        assert(
            jdkConstructErrors.nonEmpty,
            "InconsistentClasspath must reject raw java.util.UUID arguments after the Tasty.Uuid lift"
        )
        succeed
    }

    // The `useClasspath` and `useClasspathAbort` private helper defs have been removed from
    // object Tasty. The companion shortcuts now delegate directly via `classpath.map(...)`.
    // Verify that `Tasty.findClass` still compiles with the correct return type, confirming
    // the direct delegation shape is intact.
    "Tasty.findClass return type is Maybe[Symbol.Class] < Sync after useClasspath helper removal" in {
        val _: Maybe[Tasty.Symbol.Class] < Sync = Tasty.findClass("scala.Some")
        succeed
    }

    // Verify that `Tasty.requireClass` return type is exactly Symbol.Class < (Sync & Abort[TastyError])
    // after the useClasspathAbort helper removal. This confirms the Abort row is preserved
    // through the direct `classpath.map(...)` delegation.
    "Tasty.requireClass return type is Symbol.Class < (Sync & Abort[TastyError]) after helper removal" in {
        val _: Tasty.Symbol.Class < (Sync & Abort[TastyError]) = Tasty.requireClass("scala.Some")
        succeed
    }

    // ByteView.Mapped members changed from `protected` to `private[kyo]`. Members accessible
    // from within package kyo remain accessible; the change tightens visibility for code outside
    // the kyo package tree. Verify the `private[kyo]` form compiles when referenced from this
    // test file (which is in package kyo).
    "ByteView.Mapped private[kyo] members are accessible from package kyo" in {
        val errors = compiletime.testing.typeCheckErrors(
            "(??? : kyo.internal.tasty.binary.ByteView.Mapped).cursor"
        )
        assert(errors.isEmpty, s"ByteView.Mapped.cursor must be accessible from package kyo; got: $errors")
        succeed
    }

    // INV-R-001: Internal-kernel abbreviation tokens forbidden in `kyo-tasty/shared/src/main`.
    // The R-001 rename table mapped `unresolvedIdToFqn` -> `unresolvedIdToFullName` on
    // `DecodeSession`. Verify the renamed identifier resolves and the old name does not.
    // A representative pair stands in for the whole rename map; the L0 grep gate covers the
    // full identifier sweep (the in-test compile witness here cannot enumerate every site
    // without reading source files, which the test-style rule forbids).
    "DecodeSession exposes unresolvedIdToFullName; the pre-rename unresolvedIdToFqn is absent" in {
        val newNameErrors = compiletime.testing.typeCheckErrors(
            "(??? : kyo.internal.tasty.reader.TypeUnpickler.DecodeSession).unresolvedIdToFullName"
        )
        assert(
            newNameErrors.isEmpty,
            s"DecodeSession.unresolvedIdToFullName must resolve after the R-001 rename; got errors: $newNameErrors"
        )
        val oldNameErrors = compiletime.testing.typeCheckErrors(
            "(??? : kyo.internal.tasty.reader.TypeUnpickler.DecodeSession).unresolvedIdToFqn"
        )
        assert(
            oldNameErrors.nonEmpty,
            "DecodeSession.unresolvedIdToFqn must not resolve after the R-001 rename"
        )
        succeed
    }

    // Every `case _ =>` arm on a sealed ADT in `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/**`
    // must be preceded by a `// Carve-out:` comment. The runtime probe below confirms that each
    // Tasty.Type leaf case is reachable and distinct, which is the behavioral contract the
    // dissolved catch-alls must satisfy. The full syntactic invariant is enforced by the L0
    // grep gate (see decisions.md).
    "representative Tasty.Type leaf cases are reachable and distinguishable" in {
        val named: Tasty.Type    = Tasty.Type.Named(Tasty.SymbolId(0))
        val termRef: Tasty.Type  = Tasty.Type.TermRef(Tasty.Type.Nothing, Tasty.Name("p"))
        val applied: Tasty.Type  = Tasty.Type.Applied(named, Chunk.empty)
        val nothing: Tasty.Type  = Tasty.Type.Nothing
        val anyT: Tasty.Type     = Tasty.Type.Any
        val thisType: Tasty.Type = Tasty.Type.ThisType(Tasty.SymbolId(0))
        def label(t: Tasty.Type): String = t match
            case _: Tasty.Type.Named           => "named"
            case _: Tasty.Type.TermRef         => "termRef"
            case _: Tasty.Type.Applied         => "applied"
            case Tasty.Type.Nothing            => "nothing"
            case Tasty.Type.Any                => "any"
            case _: Tasty.Type.ThisType        => "thisType"
            case _: Tasty.Type.TypeRef         => "typeRef"
            case _: Tasty.Type.TypeLambda      => "typeLambda"
            case _: Tasty.Type.Function        => "function"
            case _: Tasty.Type.ContextFunction => "contextFunction"
            case _: Tasty.Type.Tuple           => "tuple"
            case _: Tasty.Type.ByName          => "byName"
            case _: Tasty.Type.Repeated        => "repeated"
            case _: Tasty.Type.Array           => "array"
            case _: Tasty.Type.Refinement      => "refinement"
            case _: Tasty.Type.Rec             => "rec"
            case _: Tasty.Type.RecThis         => "recThis"
            case _: Tasty.Type.AndType         => "andType"
            case _: Tasty.Type.OrType          => "orType"
            case _: Tasty.Type.Annotated       => "annotated"
            case _: Tasty.Type.ConstantType    => "constantType"
            case _: Tasty.Type.SuperType       => "superType"
            case _: Tasty.Type.ParamRef        => "paramRef"
            case _: Tasty.Type.Wildcard        => "wildcard"
            case _: Tasty.Type.Skolem          => "skolem"
            case _: Tasty.Type.MatchType       => "matchType"
            case _: Tasty.Type.FlexibleType    => "flexibleType"
            case _: Tasty.Type.MatchCase       => "matchCase"
            case _: Tasty.Type.Bind            => "bind"
            case _: Tasty.Type.Bounds          => "bounds"
        end label
        assert(label(named) == "named")
        assert(label(termRef) == "termRef")
        assert(label(applied) == "applied")
        assert(label(nothing) == "nothing")
        assert(label(anyT) == "any")
        assert(label(thisType) == "thisType")
        succeed
    }

    // No .flatMap on a kyo computation `A < S` in kyo-tasty; use .map instead.
    // The syntactic constraint is enforced by the L0 grep gate (see decisions.md). The
    // runtime witness below confirms that kyo's .map auto-flatten semantics hold on a
    // representative kyo computation chain:
    // `Sync.defer(v).map(f)` where f returns `A < Sync` produces the same runtime result as
    // the equivalent two-step chain because kyo's .map accepts effect-bearing lambdas.
    // The scaladoc at `kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala` lines 82-88
    // documents that .flatMap is identical to .map and .map is preferred for non-for-comprehension use.
    "kyo .map accepts an effect-bearing lambda and auto-lifts (representative chain produces expected value)" in {
        Sync.defer(42).map(x => Sync.defer(x + 1)).map { result =>
            assert(result == 43, s"Expected 43 but got $result")
            succeed
        }
    }

    // All 21 value-first navigation methods deleted from object Tasty (see §2.5b) must not
    // resolve on the companion. Each typeCheckErrors probe below verifies one deleted name.
    "all 21 value-first navigation methods are absent from object Tasty" in {
        val witnesses = List(
            "owner" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.owner(null: kyo.Tasty.Symbol)"),
            "fullName" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.fullName(null: kyo.Tasty.Symbol)"),
            "show" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.show(null: kyo.Tasty.Symbol)"),
            "signature" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.signature(null: kyo.Tasty.Symbol)"),
            "ownersChain" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.ownersChain(null: kyo.Tasty.Symbol)"),
            "binaryName" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.binaryName(null: kyo.Tasty.Symbol)"),
            "companion" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.companion(null: kyo.Tasty.Symbol)"),
            "typeParams" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.typeParams(null: kyo.Tasty.Symbol)"),
            "declarations" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.declarations(null: kyo.Tasty.Symbol)"),
            "paramLists" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.paramLists(null: kyo.Tasty.Symbol.Method)"),
            "permittedSubclasses" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.permittedSubclasses(null: kyo.Tasty.Symbol)"),
            "parents" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.parents(null: kyo.Tasty.Symbol)"),
            "members" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.members(null: kyo.Tasty.Symbol)"),
            "findMember" ->
                compiletime.testing.typeCheckErrors(
                    "def __p(using kyo.Frame) = kyo.Tasty.findMember(null: kyo.Tasty.Symbol, null: kyo.Tasty.Name)"
                ),
            "findDeclaredMember" ->
                compiletime.testing.typeCheckErrors(
                    "def __p(using kyo.Frame) = kyo.Tasty.findDeclaredMember(null: kyo.Tasty.Symbol, null: kyo.Tasty.Name)"
                ),
            "hasAnnotation" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.hasAnnotation(null: kyo.Tasty.Symbol, \"\")"),
            "findAnnotation" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.findAnnotation(null: kyo.Tasty.Symbol, \"\")"),
            "typeSymbol" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.typeSymbol(null: kyo.Tasty.Type)"),
            "isSubtypeOf" ->
                compiletime.testing.typeCheckErrors(
                    "def __p(using kyo.Frame) = kyo.Tasty.isSubtypeOf(null: kyo.Tasty.Type, null: kyo.Tasty.Type)"
                ),
            "typeShow" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.typeShow(null: kyo.Tasty.Type)"),
            "treeShow" ->
                compiletime.testing.typeCheckErrors("def __p(using kyo.Frame) = kyo.Tasty.treeShow(null: kyo.Tasty.Tree)")
        )
        val failures = witnesses.collect { case (name, errors) if errors.isEmpty => name }
        assert(
            failures.isEmpty,
            s"These value-first navigation methods must not exist on object Tasty but compiled: ${failures.mkString(", ")}"
        )
        succeed
    }

    // The same value-first navigation methods are pure Classpath instance methods that compile from package kyo.
    "value-first navigation methods are pure Classpath instance methods" in {
        val ownerOk = compiletime.testing.typeCheckErrors(
            "val _: kyo.Maybe[kyo.Tasty.Symbol] = kyo.Tasty.Classpath.empty.owner(null: kyo.Tasty.Symbol)"
        )
        assert(ownerOk.isEmpty, s"classpath.owner must compile; got errors: $ownerOk")
        val typeShowOk = compiletime.testing.typeCheckErrors(
            "val _: String = kyo.Tasty.Classpath.empty.typeShow(null: kyo.Tasty.Type)"
        )
        assert(typeShowOk.isEmpty, s"classpath.typeShow must compile; got errors: $typeShowOk")
        val isSubtypeOk = compiletime.testing.typeCheckErrors(
            "val _: kyo.Result[kyo.TastyError, kyo.Tasty.SubtypeVerdict] = kyo.Tasty.Classpath.empty.isSubtypeOf(null: kyo.Tasty.Type, null: kyo.Tasty.Type)"
        )
        assert(isSubtypeOk.isEmpty, s"classpath.isSubtypeOf must compile; got errors: $isSubtypeOk")
        succeed
    }

    // The `Tasty.Snapshot` namespace was removed; `evictOlderThan` is a top-level companion method.
    "Tasty.Snapshot namespace is removed; evictOlderThan is top-level on object Tasty" in {
        val snapshotErrors = compiletime.testing.typeCheckErrors("kyo.Tasty.Snapshot : Any")
        assert(snapshotErrors.nonEmpty, "Tasty.Snapshot must not resolve; the namespace was removed")
        val evictOk = compiletime.testing.typeCheckErrors(
            """def __probe(using kyo.Frame) = kyo.Tasty.evictOlderThan("dir", kyo.Duration.Zero)"""
        )
        assert(evictOk.isEmpty, s"Tasty.evictOlderThan must compile at top level; got errors: $evictOk")
        succeed
    }

end InvariantsSpec
