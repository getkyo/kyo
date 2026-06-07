package kyo

import kyo.internal.tasty.symbol.SymbolKind
import scala.compiletime.testing.typeCheckErrors

/** Tests for SubtypeVerdict.Unknown renamed to Indeterminate, and
  * TastyError.UnhandledSubtypingCase new variant.
  *
  * Leaves:
  *   1. indeterminateRenamedFromUnknown
  *   2. budgetExhaustionYieldsIndeterminate
  *   3. unhandledShapeRoutesToCpErrors
  *   4. pureVerdictSignatureUnchanged
  *   5. schemaRoundTripsSubtypeVerdict
  *   6. unhandledSubtypingCaseIsClosedEnumVariant
  */
class IsSubtypeOfTest extends kyo.test.Test[Any]:

    import kyo.Tasty.SymbolId

    private var nextId: Int = 0
    private def freshId(): SymbolId =
        val id = nextId
        nextId += 1
        SymbolId(id)
    end freshId

    private def makeSym(fqn: String, parents: Chunk[Tasty.Type] = Chunk.empty): Tasty.Symbol.Class =
        val leafName = fqn.split("\\.").last
        Tasty.Symbol.Class(
            id = freshId(),
            name = Tasty.Name(leafName),
            flags = Tasty.Flags.empty,
            ownerId = SymbolId(-1),
            scaladoc = Maybe.Absent,
            sourcePosition = Maybe.Absent,
            javaMetadata = Maybe.Absent,
            parentTypes = parents,
            typeParamIds = Chunk.empty,
            declarationIds = Chunk.empty,
            permittedSubclassIds = Maybe.Absent,
            annotations = Chunk.empty,
            javaAnnotations = Chunk.empty
        )
    end makeSym

    private def makeTestClasspath(syms: Chunk[Tasty.Symbol])(using Frame): Tasty.Classpath < Sync =
        val maxId = syms.foldLeft(-1)((m, s) => math.max(m, s.id.value))
        val arr   = new Array[Tasty.Symbol](maxId + 1)
        val sentinel =
            Tasty.Symbol.Package(Tasty.SymbolId(-1), Tasty.Name("<sentinel>"), Tasty.Flags.empty, Tasty.SymbolId(-1), Chunk.empty)
        var fi = 0
        while fi <= maxId do
            arr(fi) = sentinel
            fi += 1
        end while
        for sym <- syms do
            val idx = sym.id.value
            if idx >= 0 then arr(idx) = sym
        end for
        Tasty.Classpath.fromPicklesWithSymbols(Chunk.from(arr))
    end makeTestClasspath

    // SubtypeVerdict.Unknown no longer exists (renamed to Indeterminate).
    "leaf-1: SubtypeVerdict.Unknown does not exist (compile-time probe)" in {
        val errors = typeCheckErrors("val _: kyo.Tasty.SubtypeVerdict = kyo.Tasty.SubtypeVerdict.Unknown")
        assert(errors.nonEmpty, "Expected compile error for SubtypeVerdict.Unknown but got none")
        succeed
    }

    // Budget exhaustion yields Indeterminate (not Unknown).
    "leaf-2: budget exhaustion via 66-deep Rec chain yields Indeterminate" in {
        nextId = 0
        val leafSym          = makeSym("RecBudgetLeaf")
        val leaf: Tasty.Type = Tasty.Type.Named(leafSym.id)
        var t: Tasty.Type    = leaf
        var i                = 0
        while i < 66 do
            t = Tasty.Type.Rec(t)
            i += 1
        end while
        makeTestClasspath(Chunk(leafSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                Tasty.isSubtypeOf(t, t).map: result =>
                    assert(
                        result == Tasty.SubtypeVerdict.Indeterminate,
                        s"Expected Indeterminate from budget exhaustion but got $result"
                    )
                    succeed
    }

    // Unhandled parent shape accumulates in cp.errors (binding).
    // Uses a Symbol.Class whose parentTypes contains a TermRef (not Named/Applied(Named)),
    // which is outside the matcher set in checkParents. After isSubtypeOf, calling
    // Tasty.classpath folds any accumulated decodeCtx.subtypingErrors into cp.errors.
    "leaf-3: unhandled parent shape routes to cp.errors" in {
        nextId = 0
        val baseSym  = makeSym("test.Base")
        val baseId   = baseSym.id
        val baseType = Tasty.Type.Named(baseId)

        // Create a TermRef parent (not Named, not Applied(Named)) to trigger the unhandled case.
        val termRefParent = Tasty.Type.TermRef(baseType, Tasty.Name("termRefParent"))

        val subSym = makeSym("test.Sub", Chunk(termRefParent))
        val supSym = makeSym("test.Sup")

        val subType = Tasty.Type.Named(subSym.id)
        val supType = Tasty.Type.Named(supSym.id)

        makeTestClasspath(Chunk(baseSym, subSym, supSym)).flatMap: cp =>
            Tasty.withClasspath(cp):
                for
                    verdict <- Tasty.isSubtypeOf(subType, supType)
                    finalCp <- Tasty.classpath
                yield
                    assert(
                        verdict == Tasty.SubtypeVerdict.Indeterminate,
                        s"Expected Indeterminate for unhandled parent shape but got $verdict"
                    )
                    val errors = finalCp.errors
                    assert(
                        errors.size == 1,
                        s"Expected exactly 1 UnhandledSubtypingCase error but got ${errors.size}: $errors"
                    )
                    errors.head match
                        case TastyError.UnhandledSubtypingCase(shape, _, _, file) =>
                            assert(shape == "TermRef", s"Expected shape='TermRef' but got '$shape'")
                            assert(file == "<unknown>", s"Expected file='<unknown>' but got '$file'")
                        case other =>
                            fail(s"Expected UnhandledSubtypingCase but got $other")
                    end match
                    succeed
    }

    // Signature of isSubtypeOf is SubtypeVerdict < Sync (no Abort[TastyError]).
    // This helper method must compile for the test to pass; its return type annotation is the assertion.
    "leaf-4: isSubtypeOf return type is SubtypeVerdict < Sync (no Abort row)" in {
        def checkSignature(using Frame): Tasty.SubtypeVerdict < Sync =
            Tasty.withClasspath(Tasty.Classpath.empty):
                Tasty.isSubtypeOf(Tasty.Type.Nothing, Tasty.Type.Any)
        succeed
    }

    // Schema round-trips each SubtypeVerdict case via JSON.
    "leaf-5: Schema round-trips Sub, NotSub, Indeterminate" in {
        for verdict <- Seq(
                Tasty.SubtypeVerdict.Sub,
                Tasty.SubtypeVerdict.NotSub,
                Tasty.SubtypeVerdict.Indeterminate
            )
        do
            val encoded = Json.encode(verdict)
            Json.decode[Tasty.SubtypeVerdict](encoded) match
                case Result.Success(v) =>
                    assert(v == verdict, s"Schema round-trip failed: encoded $verdict, decoded $v")
                case Result.Failure(e) =>
                    fail(s"Schema decode failed for $verdict: $e")
                case Result.Panic(t) =>
                    throw t
            end match
        end for
        succeed
    }

    // TastyError.UnhandledSubtypingCase is a closed-enum variant: constructable,
    // matchable, and its omission from a match causes an exhaustiveness compile error.
    "leaf-6: UnhandledSubtypingCase is a reachable, closed-enum TastyError variant" in {
        // Reachability: the variant can be constructed and matched.
        val e: TastyError = TastyError.UnhandledSubtypingCase(
            shape = "TermRef",
            lhs = Tasty.Type.Any,
            rhs = Tasty.Type.Nothing,
            file = "X.tasty"
        )
        e match
            case TastyError.UnhandledSubtypingCase(shape, lhs, rhs, file) =>
                assert(shape == "TermRef")
                assert(lhs == Tasty.Type.Any)
                assert(rhs == Tasty.Type.Nothing)
                assert(file == "X.tasty")
            case other =>
                fail(s"Expected UnhandledSubtypingCase but got $other")
        end match

        // Closed-enum exhaustiveness: a match on TastyError that includes UnhandledSubtypingCase
        // compiles cleanly; if the variant were removed, this match would produce a compile error.
        // (The SealedAdtCompletenessTest Mirror guard enforces count at compile time; this arm
        // confirms the variant is syntactically matchable and closes the positive pin.)
        val label: String = e match
            case TastyError.FileNotFound(_)                    => "FileNotFound"
            case TastyError.CorruptedFile(_, _, _)             => "CorruptedFile"
            case TastyError.UnsupportedVersion(_, _)           => "UnsupportedVersion"
            case TastyError.InconsistentClasspath(_, _, _)     => "InconsistentClasspath"
            case TastyError.FqnCollisionError(_)               => "FqnCollisionError"
            case TastyError.MalformedSection(_, _, _)          => "MalformedSection"
            case TastyError.SymbolNotFound(_)                  => "SymbolNotFound"
            case TastyError.NotFound(_)                        => "NotFound"
            case TastyError.ClassfileFormatError(_, _, _)      => "ClassfileFormatError"
            case TastyError.ClasspathClosed(_)                 => "ClasspathClosed"
            case TastyError.ClasspathBuilding(_)               => "ClasspathBuilding"
            case TastyError.SnapshotFormatError(_, _, _)       => "SnapshotFormatError"
            case TastyError.SnapshotVersionMismatch(_, _)      => "SnapshotVersionMismatch"
            case TastyError.SnapshotIoError(_)                 => "SnapshotIoError"
            case TastyError.NotImplemented(_)                  => "NotImplemented"
            case TastyError.UnsupportedPlatform(_)             => "UnsupportedPlatform"
            case TastyError.UnknownTagInPosition(_, _)         => "UnknownTagInPosition"
            case TastyError.InvalidFqn(_, _)                   => "InvalidFqn"
            case TastyError.DigestMismatch(_, _)               => "DigestMismatch"
            case TastyError.UnhandledSubtypingCase(_, _, _, _) => "UnhandledSubtypingCase"
        assert(label == "UnhandledSubtypingCase", s"Expected UnhandledSubtypingCase label but got $label")
        succeed
    }

end IsSubtypeOfTest
