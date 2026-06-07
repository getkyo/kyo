package kyo

import AllowUnsafe.embrace.danger
import kyo.Tasty.SymbolId

/** followup for W-05-02: exercises `TypeParam.varianceLabel`.
  *
  * W-05-02 noted that `varianceLabel` was listed in the top-level public-API delta for but was never implemented or tested. This
  * test validates the addition.
  */
class TypeParamVarianceLabelTest extends kyo.test.Test[Any]:

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

    "varianceLabel returns empty string for Invariant" in {
        val tp = makeTypeParam(1, Tasty.Variance.Invariant)
        assert(
            (tp.variance match
                case Tasty.Variance.Covariant     => "+";
                case Tasty.Variance.Contravariant => "-";
                case _                            => ""
            ) == "",
            s"Expected '' for Invariant, got '${(tp.variance match
                    case Tasty.Variance.Covariant     => "+";
                    case Tasty.Variance.Contravariant => "-";
                    case _                            => ""
                )}'"
        )
        succeed
    }

    "varianceLabel returns '+' for Covariant" in {
        val tp = makeTypeParam(2, Tasty.Variance.Covariant)
        assert(
            (tp.variance match
                case Tasty.Variance.Covariant     => "+";
                case Tasty.Variance.Contravariant => "-";
                case _                            => ""
            ) == "+",
            s"Expected '+' for Covariant, got '${(tp.variance match
                    case Tasty.Variance.Covariant     => "+";
                    case Tasty.Variance.Contravariant => "-";
                    case _                            => ""
                )}'"
        )
        succeed
    }

    "varianceLabel returns '-' for Contravariant" in {
        val tp = makeTypeParam(3, Tasty.Variance.Contravariant)
        assert(
            (tp.variance match
                case Tasty.Variance.Covariant     => "+";
                case Tasty.Variance.Contravariant => "-";
                case _                            => ""
            ) == "-",
            s"Expected '-' for Contravariant, got '${(tp.variance match
                    case Tasty.Variance.Covariant     => "+";
                    case Tasty.Variance.Contravariant => "-";
                    case _                            => ""
                )}'"
        )
        succeed
    }

    "varianceLabel exhaustive match: all three Variance cases covered" in {
        val labels = Seq(
            (makeTypeParam(1, Tasty.Variance.Invariant).variance match
                case Tasty.Variance.Covariant     => "+";
                case Tasty.Variance.Contravariant => "-";
                case _                            => ""
            ),
            (makeTypeParam(2, Tasty.Variance.Covariant).variance match
                case Tasty.Variance.Covariant     => "+";
                case Tasty.Variance.Contravariant => "-";
                case _                            => ""
            ),
            (makeTypeParam(3, Tasty.Variance.Contravariant).variance match
                case Tasty.Variance.Covariant     => "+";
                case Tasty.Variance.Contravariant => "-";
                case _                            => ""
            )
        )
        assert(labels.toSet == Set("", "+", "-"), s"Expected {'', '+', '-'} but got ${labels.toSet}")
        succeed
    }

end TypeParamVarianceLabelTest
