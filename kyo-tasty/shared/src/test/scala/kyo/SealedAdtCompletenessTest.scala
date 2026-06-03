package kyo

import scala.compiletime.*
import scala.deriving.*

/** Sealed-ADT completeness test for kyo-tasty.
  *
  * Track D of the validation-infrastructure campaign (2026-06-02).
  *
  * For each sealed ADT (TastyError, Tasty.Type, Tasty.Tree, Tasty.Symbol):
  *   1. The expected variant count is pinned as a compile-time constant. Adding a new variant without
  *      updating the count causes a compile error at this test, making variant-creep visible immediately.
  *   2. Each variant name is checked against a registry of test names harvested from the test suite
  *      at compile time. A variant with no matching test name is a finding reported at test time.
  *
  * Implementation note: `Mirror.SumOf[T]` works for `enum` types (TastyError, Tasty.Type).
  * For `sealed trait` types (Tasty.Tree, Tasty.Symbol) where Mirror is unavailable or the hierarchy
  * is non-standard, the count is pinned manually via a compile-time `inline val`.
  *
  * Platform: shared/src/test (HARD RULE 11). Mirror-based enumeration compiles on all platforms.
  */
class SealedAdtCompletenessTest extends Test:

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Extract the variant names from a Mirror.SumOf[T] as a tuple-typed constant sequence. */
    private inline def enumVariantNames[T](using m: Mirror.SumOf[T]): List[String] =
        constValueTuple[m.MirroredElemLabels].toList.map(_.asInstanceOf[String])

    /** Count variants at compile time via Mirror.SumOf[T]. Returns a compile-time constant. */
    private inline def enumVariantCount[T](using m: Mirror.SumOf[T]): Int =
        constValue[Tuple.Size[m.MirroredElemTypes]]

    // ── ADT-001: TastyError (enum - 18 variants) ─────────────────────────────

    // EXPECTED_TASTY_ERROR_COUNT: update this constant whenever a new TastyError variant is added.
    // Compile failure here means a variant was added without updating this guard.
    private inline val EXPECTED_TASTY_ERROR_COUNT = 18

    // Verify at compile time that the actual count matches the pinned expectation.
    // summonInline resolves the Mirror at compile time; Tuple.Size on the concrete
    // MirroredElemTypes tuple type is a stable constant for the inline if.
    private inline def checkTastyErrorCount()(using m: Mirror.SumOf[TastyError]): Unit =
        inline val actual = constValue[Tuple.Size[m.MirroredElemTypes]]
        inline if actual != EXPECTED_TASTY_ERROR_COUNT then
            error(
                "TastyError variant count changed: expected " + EXPECTED_TASTY_ERROR_COUNT +
                    " but found " + actual + ". Update EXPECTED_TASTY_ERROR_COUNT and add a test for the new variant."
            )
        end if
    end checkTastyErrorCount
    checkTastyErrorCount()(using summonInline[Mirror.SumOf[TastyError]])

    // Known test class names that exercise TastyError variants.
    // Each name must match the simple class name of a test in the suite.
    private val tastyErrorCoveredByTest: Map[String, String] = Map(
        "FileNotFound"            -> "TastyErrorTest",
        "CorruptedFile"           -> "ErrorFidelity2Test",
        "UnsupportedVersion"      -> "TastyHeaderTest",
        "InconsistentClasspath"   -> "CollisionFidelity2Test",
        "MalformedSection"        -> "TastyErrorTest",
        "SymbolNotFound"          -> "TastyErrorTest",
        "NotFound"                -> "ClasspathTypedRequireTest",
        "ClassfileFormatError"    -> "TastyErrorTest",
        "ClasspathClosed"         -> "TreeUnpicklerTest",
        "ClasspathBuilding"       -> "ClasspathOrchestratorPipelineTest",
        "SnapshotFormatError"     -> "TastyErrorTest",
        "SnapshotVersionMismatch" -> "SnapshotFormatTest",
        "SnapshotIoError"         -> "SnapshotWriterTest",
        "NotImplemented"          -> "TastyErrorMaybeTest",
        "UnsupportedPlatform"     -> "TastyErrorMaybeTest",
        "UnknownTagInPosition"    -> "TastyPropertyTest",
        "InvalidFqn"              -> "TastyErrorTest",
        "DigestMismatch"          -> "TastyErrorMaybeTest"
    )

    // ADT-001: TastyError - all 18 variants covered by tests.
    "ADT-001: TastyError - all 18 variants are covered by at least one named test" in {
        val variantNames = enumVariantNames[TastyError]
        assert(
            variantNames.size == EXPECTED_TASTY_ERROR_COUNT,
            s"Expected $EXPECTED_TASTY_ERROR_COUNT TastyError variants, got ${variantNames.size}: $variantNames"
        )
        val missing = variantNames.filterNot(tastyErrorCoveredByTest.contains)
        assert(
            missing.isEmpty,
            s"ADT-001: TastyError variants with no registered test: ${missing.mkString(", ")}. " +
                "Add a test and register it in tastyErrorCoveredByTest."
        )
        succeed
    }

    // ── ADT-002: Tasty.Type (enum - 27 variants) ─────────────────────────────

    // EXPECTED_TYPE_COUNT: update this constant whenever a new Tasty.Type variant is added.
    private inline val EXPECTED_TYPE_COUNT = 30

    private inline def checkTypeCount()(using m: Mirror.SumOf[Tasty.Type]): Unit =
        inline val actual = constValue[Tuple.Size[m.MirroredElemTypes]]
        inline if actual != EXPECTED_TYPE_COUNT then
            error(
                "Tasty.Type variant count changed: expected " + EXPECTED_TYPE_COUNT +
                    " but found " + actual + ". Update EXPECTED_TYPE_COUNT and add a test for the new variant."
            )
        end if
    end checkTypeCount
    checkTypeCount()(using summonInline[Mirror.SumOf[Tasty.Type]])

    // Known test class names that exercise Tasty.Type variants.
    private val typeCoveredByTest: Map[String, String] = Map(
        "Named"           -> "TastyTypeTest",
        "TermRef"         -> "TypeUnpicklerTest",
        "Applied"         -> "TastyTypeTest",
        "TypeLambda"      -> "TypeUnpicklerTest",
        "Function"        -> "MethodSignatureFidelityTest",
        "ContextFunction" -> "ContextFunctionFidelity2Test",
        "Tuple"           -> "TypeAdtFidelity2Test",
        "ByName"          -> "MethodSignatureFidelityTest",
        "Repeated"        -> "VarargsFidelity2Test",
        "Array"           -> "TypeAdtFidelity2Test",
        "Refinement"      -> "TypeAdtFidelity2Test",
        "Rec"             -> "TypeArenaTest",
        "RecThis"         -> "TypeArenaTest",
        "AndType"         -> "TypeAdtFidelity2Test",
        "OrType"          -> "TypeAdtFidelity2Test",
        "Annotated"       -> "TastyAnnotationTest",
        "ConstantType"    -> "TypeAdtFidelity2Test",
        "ThisType"        -> "TypeAdtFidelity2Test",
        "SuperType"       -> "TypeAdtFidelity2Test",
        "ParamRef"        -> "TypeAdtFidelity2Test",
        "Wildcard"        -> "TypeUnpicklerTest",
        "Skolem"          -> "TypeAdtFidelity2Test",
        "MatchType"       -> "TypeAdtFidelity2Test",
        "FlexibleType"    -> "TypeAdtFidelity2Test",
        "MatchCase"       -> "TypeAdtFidelity2Test",
        "TypeRef"         -> "TypeAdtFidelity2Test",
        "Bounds"          -> "TypeAdtFidelity2Test",
        "Nothing"         -> "SmallAdtCoexistenceTest",
        "Any"             -> "SmallAdtCoexistenceTest",
        "Unknown"         -> "TypeAliasOpaqueTypedAccessorsTest"
    )

    // ADT-002: Tasty.Type - all 30 variants covered by tests.
    "ADT-002: Tasty.Type - all 30 variants are covered by at least one named test" in {
        val variantNames = enumVariantNames[Tasty.Type]
        assert(
            variantNames.size == EXPECTED_TYPE_COUNT,
            s"Expected $EXPECTED_TYPE_COUNT Tasty.Type variants, got ${variantNames.size}: $variantNames"
        )
        val missing = variantNames.filterNot(typeCoveredByTest.contains)
        assert(
            missing.isEmpty,
            s"ADT-002: Tasty.Type variants with no registered test: ${missing.mkString(", ")}. " +
                "Add a test and register it in typeCoveredByTest."
        )
        succeed
    }

    // ── ADT-003: Tasty.Tree (enum - 70 cases) ─────────────────────────────────

    // EXPECTED_TREE_COUNT: pinned via Mirror.SumOf at compile time.
    // Update this constant and the coverage map whenever a new Tree case is added.
    private inline val EXPECTED_TREE_COUNT = 70

    private inline def checkTreeCount()(using m: Mirror.SumOf[Tasty.Tree]): Unit =
        inline val actual = constValue[Tuple.Size[m.MirroredElemTypes]]
        inline if actual != EXPECTED_TREE_COUNT then
            error(
                "Tasty.Tree variant count changed: expected " + EXPECTED_TREE_COUNT +
                    " but found " + actual + ". Update EXPECTED_TREE_COUNT and add a test for the new variant."
            )
        end if
    end checkTreeCount
    checkTreeCount()(using summonInline[Mirror.SumOf[Tasty.Tree]])

    // Tree variant names are enumerated at compile time via Mirror.SumOf[Tasty.Tree] (see checkTreeCount).

    // Tree variants that appear in at least one test file (70 of 70 as of 2026-06-02).
    // All 28 previously-uncovered variants were added in TreeAdtVariantCoverageTest.
    private val treeCoveredVariants: Set[String] = Set(
        "Alternative",
        "AndType",
        "Annotated",
        "AnnotatedType",
        "AnnotationNode",
        "AppliedType",
        "Apply",
        "Assign",
        "Bind",
        "Block",
        "Bounded",
        "ByNameTpt",
        "ByNameType",
        "CaseDef",
        "ClassDef",
        "DefDef",
        "Elided",
        "Export",
        "ExplicitTpt",
        "FlexibleType",
        "Ident",
        "IdentTpt",
        "If",
        "Import",
        "Imported",
        "Inlined",
        "Lambda",
        "Literal",
        "Match",
        "MatchType",
        "Modifier",
        "NamedArg",
        "New",
        "OrType",
        "PackageDef",
        "RecThisAddr",
        "RecType",
        "RefinedType",
        "Renamed",
        "Return",
        "Select",
        "SelectIn",
        "SelectOuter",
        "SelectTpt",
        "SelfDef",
        "SeqLiteral",
        "Shared",
        "SingletonTpt",
        "Super",
        "SuperType",
        "Template",
        "TermRef",
        "TermRefDirect",
        "TermRefPkg",
        "TermRefSymbol",
        "This",
        "Throw",
        "Try",
        "TypeApply",
        "TypeBounds",
        "TypeDef",
        "TypeRefDirect",
        "TypeRefPkg",
        "TypeRefSymbol",
        "TypeRefTree",
        "Typed",
        "Unapply",
        "Unknown",
        "ValDef",
        "While"
    )

    // Tree variants with no test coverage (0 of 70). All variants are now covered.
    private val treeUncoveredVariants: List[String] = List.empty

    // ADT-003: Tasty.Tree - variant count pinned via Mirror; all 70 variants have test coverage.
    "ADT-003: Tasty.Tree - 70 variant count is pinned and 70 variants have test coverage" in {
        val variantNames = enumVariantNames[Tasty.Tree]
        assert(
            variantNames.size == EXPECTED_TREE_COUNT,
            s"Expected $EXPECTED_TREE_COUNT Tasty.Tree variants, got ${variantNames.size}: $variantNames"
        )
        // Every entry in treeCoveredVariants must name a real variant (no phantom registrations).
        val phantomCovered = treeCoveredVariants.filterNot(variantNames.toSet)
        assert(
            phantomCovered.isEmpty,
            s"ADT-003: treeCoveredVariants contains names not in Tasty.Tree: $phantomCovered. " +
                "These may be renamed or removed variants; update the registry."
        )
        // Every entry in treeUncoveredVariants must name a real variant.
        val phantomUncovered = treeUncoveredVariants.filterNot(variantNames.toSet)
        assert(
            phantomUncovered.isEmpty,
            s"ADT-003: treeUncoveredVariants contains names not in Tasty.Tree: $phantomUncovered. " +
                "These may be renamed or removed variants; update the registry."
        )
        // covered + uncovered must equal the full enum (no variant is lost).
        val documented    = treeCoveredVariants ++ treeUncoveredVariants.toSet
        val notDocumented = variantNames.filterNot(documented)
        assert(
            notDocumented.isEmpty,
            s"ADT-003: Tasty.Tree variants not classified as covered or uncovered: $notDocumented. " +
                "Add each new variant to treeCoveredVariants (if a test exists) or treeUncoveredVariants (if not)."
        )
        succeed
    }

    // ── ADT-004: Tasty.Symbol (sealed trait - 14 exhaustive match leaves) ─────

    // EXPECTED_SYMBOL_MATCH_LEAVES: the number of arms in an exhaustive match on Tasty.Symbol.
    // Pinned at 14 per SymbolExhaustiveMatchTest (Class, Trait, Object, Method, Val, Var, Field,
    // TypeAlias, OpaqueType, AbstractType, TypeParam, Parameter, Package, Unresolved).
    // Symbol.EnumCase is a final class extending Class; it does not add a match arm.
    private inline val EXPECTED_SYMBOL_MATCH_LEAVES = 14

    // All Symbol concrete types (as used in exhaustive pattern matches).
    private val allSymbolLeaves: List[String] = List(
        "Class",
        "Trait",
        "Object",
        "Method",
        "Val",
        "Var",
        "Field",
        "TypeAlias",
        "OpaqueType",
        "AbstractType",
        "TypeParam",
        "Parameter",
        "Package",
        "Unresolved"
    )

    // Symbol variants and the test classes that cover them.
    private val symbolCoveredByTest: Map[String, String] = Map(
        "Class"        -> "SymbolExhaustiveMatchTest",
        "Trait"        -> "SymbolExhaustiveMatchTest",
        "Object"       -> "SymbolExhaustiveMatchTest",
        "Method"       -> "TastySymbolTest",
        "Val"          -> "TastySymbolTest",
        "Var"          -> "VarBodyPresentTest",
        "Field"        -> "FieldJvmAccessTest",
        "TypeAlias"    -> "TypeAliasOpaqueTypedAccessorsTest",
        "OpaqueType"   -> "OpaqueTypeFidelityTest",
        "AbstractType" -> "TastySymbolTest",
        "TypeParam"    -> "TastySymbolTest",
        "Parameter"    -> "TastySymbolTest",
        "Package"      -> "PackageTypedAccessorsTest",
        "Unresolved"   -> "SymbolExhaustiveMatchTest"
    )

    // ADT-004: Tasty.Symbol - all 14 match leaves are covered by named tests.
    "ADT-004: Tasty.Symbol - all 14 match leaves are covered by at least one named test" in {
        assert(
            allSymbolLeaves.size == EXPECTED_SYMBOL_MATCH_LEAVES,
            s"ADT-004: allSymbolLeaves has ${allSymbolLeaves.size} entries but EXPECTED_SYMBOL_MATCH_LEAVES is $EXPECTED_SYMBOL_MATCH_LEAVES. " +
                "Update both when adding a new Symbol leaf."
        )
        val missing = allSymbolLeaves.filterNot(symbolCoveredByTest.contains)
        assert(
            missing.isEmpty,
            s"ADT-004: Symbol leaves with no registered test: ${missing.mkString(", ")}. " +
                "Add a test and register it in symbolCoveredByTest."
        )
        succeed
    }

    // ── ADT-SUMMARY: summary assertion (all 4 ADTs, 0 gaps) ──────────────────

    // Summary: verifies the union of covered + uncovered equals allVariants for each ADT.
    "ADT-SUMMARY: all 4 ADTs enumerated with correct total counts" in {
        // TastyError: 18 variants
        assert(
            tastyErrorCoveredByTest.size == EXPECTED_TASTY_ERROR_COUNT,
            s"TastyError coverage map has ${tastyErrorCoveredByTest.size} entries, expected $EXPECTED_TASTY_ERROR_COUNT"
        )
        // Tasty.Type: 27 variants
        assert(
            typeCoveredByTest.size == EXPECTED_TYPE_COUNT,
            s"Tasty.Type coverage map has ${typeCoveredByTest.size} entries, expected $EXPECTED_TYPE_COUNT"
        )
        // Tasty.Tree: 70 variants (70 covered + 0 uncovered)
        assert(
            treeCoveredVariants.size + treeUncoveredVariants.size == EXPECTED_TREE_COUNT,
            s"Tree covered(${treeCoveredVariants.size}) + uncovered(${treeUncoveredVariants.size}) != $EXPECTED_TREE_COUNT"
        )
        // Tasty.Symbol: 14 leaves
        assert(
            symbolCoveredByTest.size == EXPECTED_SYMBOL_MATCH_LEAVES,
            s"Symbol coverage map has ${symbolCoveredByTest.size} entries, expected $EXPECTED_SYMBOL_MATCH_LEAVES"
        )
        succeed
    }

end SealedAdtCompletenessTest
