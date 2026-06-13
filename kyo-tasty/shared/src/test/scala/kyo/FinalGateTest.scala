package kyo

import scala.compiletime.testing.typeCheckErrors

/** Compile-time verification that Array fields are rejected and Chunk fields are accepted
  * by Schema + CanEqual derivation (INV-IMMUTABLE-ADT gate).
  */
class FinalGateTest extends kyo.test.Test[Any]:

    "invImmutableAdtGate" in {
        val badCount = typeCheckErrors(
            "final case class P(a: Array[Int]) derives kyo.Schema, CanEqual"
        ).length
        assert(badCount > 0, "Array[Int] field should be rejected by Schema derivation")

        val goodCount = typeCheckErrors(
            "final case class Q(a: kyo.Chunk[Int]) derives kyo.Schema, CanEqual"
        ).length
        assert(goodCount == 0, "Chunk[Int] field should be accepted by Schema derivation")
    }

end FinalGateTest
