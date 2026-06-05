package kyo

import kyo.internal.tasty.query.TastyState
import kyo.internal.tasty.symbol.SymbolKind
import scala.compiletime.testing.typeCheckErrors

/** Tests for Phase 07: SubtypeVerdict.Unknown renamed to Indeterminate, and
  * TastyError.UnhandledSubtypingCase new variant.
  *
  * Leaves:
  *   1. indeterminateRenamedFromUnknown
  *   2. budgetExhaustionYieldsIndeterminate
  *   3. unhandledShapeRoutesToCtxErrors
  *   4. pureVerdictSignatureUnchanged
  *   5. schemaRoundTripsSubtypeVerdict
  *   6. unhandledSubtypingCaseIsClosedEnumVariant
  */
class IsSubtypeOfTest extends Test:

    import AllowUnsafe.embrace.danger
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
            javaAnnotations = Chunk.empty,
            body = Maybe.Absent
        )
    end makeSym

    private def makeTestClasspath(syms: Chunk[Tasty.Symbol])(using Frame): Tasty.Classpath < Sync =
        val maxId    = syms.foldLeft(-1)((m, s) => math.max(m, s.id.value))
        val arr      = new Array[Tasty.Symbol](maxId + 1)
        val sentinel = Tasty.Symbol.makePlaceholder(SymbolKind.Unresolved, Tasty.Flags.empty, Tasty.Name("<sentinel>"))
        var fi       = 0
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

    // Leaf 1: SubtypeVerdict.Unknown no longer exists (renamed to Indeterminate).
    "leaf-1: SubtypeVerdict.Unknown does not exist (compile-time probe)" in {
        val errors = typeCheckErrors("val _: kyo.Tasty.SubtypeVerdict = kyo.Tasty.SubtypeVerdict.Unknown")
        assert(errors.nonEmpty, "Expected compile error for SubtypeVerdict.Unknown but got none")
        succeed
    }

    // Leaf 2: Budget exhaustion yields Indeterminate (not Unknown).
    "leaf-2: budget exhaustion via 66-deep Rec chain yields Indeterminate" in run {
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

    // Leaf 3: Unhandled parent shape accumulates in decodeCtx.subtypingErrors.
    // Uses a Symbol.Class whose parentTypes contains a TermRef (not Named/Applied(Named)),
    // which is outside the matcher set in checkParents.
    "leaf-3: unhandled parent shape accumulates in decodeCtx.subtypingErrors" in run {
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
                    errors <- TastyState.bindingLocal.use: mbind =>
                        Sync.defer(
                            mbind.flatMap(_.decodeCtx).map(_.subtypingErrors.toSeq).getOrElse(Seq.empty)
                        )
                yield
                    assert(
                        verdict == Tasty.SubtypeVerdict.Indeterminate,
                        s"Expected Indeterminate for unhandled parent shape but got $verdict"
                    )
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

    // Leaf 4: Signature of isSubtypeOf is SubtypeVerdict < Sync (no Abort[TastyError]).
    // This helper method must compile for the test to pass; its return type annotation is the assertion.
    "leaf-4: isSubtypeOf return type is SubtypeVerdict < Sync (no Abort row)" in {
        def checkSignature(using Frame): Tasty.SubtypeVerdict < Sync =
            Tasty.withClasspath(Tasty.Classpath.empty):
                Tasty.isSubtypeOf(Tasty.Type.Nothing, Tasty.Type.Any)
        succeed
    }

    // Leaf 5: Schema round-trips each SubtypeVerdict case via JSON.
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

    // Leaf 6: TastyError.UnhandledSubtypingCase is a closed-enum variant; constructable and matchable.
    "leaf-6: UnhandledSubtypingCase is a reachable TastyError variant" in {
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
        succeed
    }

end IsSubtypeOfTest
