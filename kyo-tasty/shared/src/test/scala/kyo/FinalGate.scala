package kyo

import scala.compiletime.testing.typeCheckErrors

/** Final-gate verification.
  *
  * Leaf 1 is a real compile-time probe that re-verifies INV-IMMUTABLE-ADT.
  *
  * INV-007; INV-011; INV-012; INV-013.
  */
class FinalGate extends Test:

    // ── Leaf 1: INV-IMMUTABLE-ADT Array-field rejection ──────────────────────
    // Given: a probe case class with an Array[Int] field attempting Schema + CanEqual derivation.
    // When: typeCheckErrors is invoked with the definition.
    // Then: errors are non-empty (Array[Int] rejected) for the bad probe;
    //       errors are empty (Chunk[Int] accepted) for the good probe.
    "invImmutableAdtGate" in {
        val bad = typeCheckErrors(
            "final case class P(a: Array[Int]) derives kyo.Schema, CanEqual"
        )
        assert(bad.nonEmpty, "Array[Int] field should be rejected by Schema derivation")

        val good = typeCheckErrors(
            "final case class Q(a: kyo.Chunk[Int]) derives kyo.Schema, CanEqual"
        )
        assert(good.isEmpty, "Chunk[Int] field should be accepted by Schema derivation")
    }

end FinalGate
