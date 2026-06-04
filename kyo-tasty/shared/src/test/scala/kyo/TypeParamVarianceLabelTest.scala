package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** Phase 08 followup for W-05-02: exercises `TypeParam.varianceLabel`.
  *
  * W-05-02 noted that `varianceLabel` was listed in the top-level public-API delta for Phase 05 but was never implemented or tested. This
  * test validates the Phase 08 addition.
  *
  * Pins: INV-002, INV-009.
  */
class TypeParamVarianceLabelTest extends Test:

    private def makeTypeParam(id: Int, v: Tasty.Variance): Tasty.Symbol.TypeParam =
        Tasty.Symbol.TypeParam(
            SymbolId(id),
            Tasty.Name("T"),
            Tasty.Flags.empty,
            SymbolId(0),
            Maybe.Absent,
            Tasty.TypeBounds(Tasty.Type.Nothing, Tasty.Type.Any),
            v
        )

    "varianceLabel returns empty string for Invariant" in run {
        val tp = makeTypeParam(1, Tasty.Variance.Invariant)
        assert(tp.varianceLabel == "", s"Expected '' for Invariant, got '${tp.varianceLabel}'")
        succeed
    }

    "varianceLabel returns '+' for Covariant" in run {
        val tp = makeTypeParam(2, Tasty.Variance.Covariant)
        assert(tp.varianceLabel == "+", s"Expected '+' for Covariant, got '${tp.varianceLabel}'")
        succeed
    }

    "varianceLabel returns '-' for Contravariant" in run {
        val tp = makeTypeParam(3, Tasty.Variance.Contravariant)
        assert(tp.varianceLabel == "-", s"Expected '-' for Contravariant, got '${tp.varianceLabel}'")
        succeed
    }

    "varianceLabel exhaustive match: all three Variance cases covered" in run {
        val labels = Seq(
            makeTypeParam(1, Tasty.Variance.Invariant).varianceLabel,
            makeTypeParam(2, Tasty.Variance.Covariant).varianceLabel,
            makeTypeParam(3, Tasty.Variance.Contravariant).varianceLabel
        )
        assert(labels.toSet == Set("", "+", "-"), s"Expected {'', '+', '-'} but got ${labels.toSet}")
        succeed
    }

end TypeParamVarianceLabelTest
