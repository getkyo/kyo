package kyo

/** Compile-time probe verifying that Array fields are rejected and Chunk fields are accepted
  * by Schema derivation (public ADT fields must use immutable Kyo collection types).
  */
class InvImmutableAdtTest extends kyo.test.Test[Any]:

    "arrayFieldRejectedOnProbe" in {
        val errors = compiletime.testing.typeCheckErrors(
            "final case class P(a: Array[Int]) derives kyo.Schema, CanEqual"
        )
        assert(errors.nonEmpty)
    }

    "chunkFieldAcceptedOnProbe" in {
        val errors = compiletime.testing.typeCheckErrors(
            "final case class Q(a: kyo.Chunk[Int]) derives kyo.Schema, CanEqual"
        )
        assert(errors.isEmpty)
    }

end InvImmutableAdtTest
