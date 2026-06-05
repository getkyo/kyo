package kyo

/** INV-IMMUTABLE-ADT compile-time probe.
  *
  * Verifies that case classes with Array fields are rejected by Schema derivation,
  * while classes with Chunk fields are accepted. This enforces the constraint that
  * public ADT fields must use immutable Kyo collection types, not raw JVM arrays.
  *
  * Leaf 1 (arrayFieldRejectedOnProbe): Array[Int] field causes a compile error.
  * Leaf 2 (chunkFieldAcceptedOnProbe): Chunk[Int] field compiles successfully.
  *
  * Pins: INV-IMMUTABLE-ADT; constraint G (produced-ADT immutability).
  */
class InvImmutableAdtTest extends Test:

    "arrayFieldRejectedOnProbe" in {
        // Given: a probe case class with an Array[Int] field attempting Schema + CanEqual derivation.
        // When: typeCheckErrors is invoked with that definition.
        // Then: the returned string is non-empty (compiler rejects Array[Int] on Schema derivation).
        val errors = compiletime.testing.typeCheckErrors(
            "final case class P(a: Array[Int]) derives kyo.Schema, CanEqual"
        )
        assert(errors.nonEmpty)
    }

    "chunkFieldAcceptedOnProbe" in {
        // Given: a probe case class with a Chunk[Int] field attempting Schema + CanEqual derivation.
        // When: typeCheckErrors is invoked with that definition.
        // Then: the returned string is empty (immutable Chunk field is accepted).
        val errors = compiletime.testing.typeCheckErrors(
            "final case class Q(a: kyo.Chunk[Int]) derives kyo.Schema, CanEqual"
        )
        assert(errors.isEmpty)
    }

end InvImmutableAdtTest
