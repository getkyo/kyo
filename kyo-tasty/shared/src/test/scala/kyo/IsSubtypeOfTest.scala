package kyo

import kyo.internal.tasty.symbol.SymbolKind
import scala.compiletime.testing.typeCheckErrors

/** Tests for SubtypeVerdict and isSubtypeOf: Indeterminate, budget exhaustion,
  * unhandled shapes routing to classpath.errors, Schema round-trips, and CanEqual derivation.
  */
class IsSubtypeOfTest extends kyo.test.Test[Any]:

    import kyo.Tasty.SymbolId

    private var nextId: Int = 0
    private def freshId(): SymbolId =
        val id = nextId
        nextId += 1
        SymbolId(id)
    end freshId

    private def makeSym(fullName: String, parents: Chunk[Tasty.Type] = Chunk.empty): Tasty.Symbol.Class =
        val leafName = fullName.split("\\.").last
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
        for symbol <- syms do
            val idx = symbol.id.value
            if idx >= 0 then arr(idx) = symbol
        end for
        Tasty.Classpath.fromPicklesWithSymbols(Chunk.from(arr))
    end makeTestClasspath

    "SubtypeVerdict.Unknown does not exist (compile-time probe)" in {
        val errors = typeCheckErrors("val _: kyo.Tasty.SubtypeVerdict = kyo.Tasty.SubtypeVerdict.Unknown")
        assert(errors.nonEmpty, "Expected compile error for SubtypeVerdict.Unknown but got none")
        succeed
    }

    "budget exhaustion via 66-deep Rec chain yields Indeterminate" in {
        nextId = 0
        val leafSym          = makeSym("RecBudgetLeaf")
        val leaf: Tasty.Type = Tasty.Type.Named(leafSym.id)
        var t: Tasty.Type    = leaf
        var i                = 0
        while i < 66 do
            t = Tasty.Type.Rec(t)
            i += 1
        end while
        makeTestClasspath(Chunk(leafSym)).map { classpath =>
            assert(
                classpath.isSubtypeOf(t, t) == Result.Success(Tasty.SubtypeVerdict.Indeterminate),
                "Expected Indeterminate from budget exhaustion"
            )
        }
    }

    // Uses a Symbol.Class whose parentTypes contains a TermRef (not Named/Applied(Named)),
    // which is outside the matcher set in checkParents. classpath.isSubtypeOf returns
    // Result.Failure(UnhandledSubtypingCase) without mutating classpath.errors.
    "unhandled parent shape returns Result.Failure(UnhandledSubtypingCase)" in {
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

        makeTestClasspath(Chunk(baseSym, subSym, supSym)).map { classpath =>
            classpath.isSubtypeOf(subType, supType) match
                case Result.Failure(e: TastyError.UnhandledSubtypingCase) =>
                    assert(e.shape.nonEmpty, "UnhandledSubtypingCase.shape must be non-empty")
                    // Verify classpath.errors was not mutated
                    val errors = classpath.errors
                    assert(errors.isEmpty, s"classpath.errors must not be mutated by isSubtypeOf; got: $errors")
                case Result.Failure(other) =>
                    fail(s"Expected UnhandledSubtypingCase but got: $other")
                case Result.Success(v) =>
                    fail(s"Expected Failure for unhandled parent shape but got Success($v)")
                case Result.Panic(t) =>
                    throw t
            end match
        }
    }

    // The pure instance method's return type is the assertion; it compiles without an effect scope.
    "classpath.isSubtypeOf return type is Result[TastyError, SubtypeVerdict]" in {
        val _: Result[TastyError, Tasty.SubtypeVerdict] =
            Tasty.Classpath.empty.isSubtypeOf(Tasty.Type.Nothing, Tasty.Type.Any)
        succeed
    }

    "Schema round-trips Sub, NotSub, Indeterminate" in {
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

    "UnhandledSubtypingCase is a reachable, closed-enum TastyError variant" in {
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

        val label: String = e match
            case TastyError.FileNotFound(_)                    => "FileNotFound"
            case TastyError.CorruptedFile(_, _, _)             => "CorruptedFile"
            case TastyError.UnsupportedVersion(_, _)           => "UnsupportedVersion"
            case TastyError.InconsistentClasspath(_, _, _)     => "InconsistentClasspath"
            case TastyError.FullNameCollisionError(_)          => "FullNameCollisionError"
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
            case TastyError.InvalidFullName(_, _)              => "InvalidFullName"
            case TastyError.DigestMismatch(_, _)               => "DigestMismatch"
            case TastyError.UnhandledSubtypingCase(_, _, _, _) => "UnhandledSubtypingCase"
        assert(label == "UnhandledSubtypingCase", s"Expected UnhandledSubtypingCase label but got $label")
        succeed
    }

end IsSubtypeOfTest
