package kyo.test.snapshot

import kyo.Modify

/** The per-call customization point for [[SnapshotTestBase.assertGoldenSnapshot]].
  *
  * An immutable builder passed to `assertGoldenSnapshot` as a `GoldenConfig[A] => GoldenConfig[A]` lambda. It carries the four golden
  * knobs (`sampleCount`, `seed`, `size`, `normalize`) so the assertion can gain new per-call capabilities over time without changing its
  * signature: each new knob is an additive method here, never a new parameter on `assertGoldenSnapshot`.
  *
  * This configures a single `assertGoldenSnapshot` invocation. The serialization format is chosen separately, per suite, by the
  * [[SnapshotTestBase.snapshotCodec]] override hook, not through this builder; the two are complementary customization surfaces at
  * different scopes (per-call here, per-suite there).
  *
  * Start from the empty config via the companion `GoldenConfig[A]` (20 samples, seed 0, size 10, identity normalization) and compose
  * builder methods, for example `_.sampleCount(50).seed(7L).normalize(_.set(_.at)(0L))` inside the `assertGoldenSnapshot` config lambda.
  *
  * @tparam A
  *   the type being sampled; a `Gen[A]` and a `Schema[A]` are required by `assertGoldenSnapshot`
  * @see
  *   [[SnapshotTestBase.assertGoldenSnapshot]] the assertion this configures
  * @see
  *   [[SnapshotConfig]] the sibling per-call builder for `assertSchemaSnapshot`
  * @see
  *   [[kyo.Modify]] the field-transform DSL that `normalize` builds on
  */
final class GoldenConfig[A] private[snapshot] (
    private[snapshot] val modify: Modify[A],
    private[snapshot] val sampleCount: Int,
    private[snapshot] val seed: Long,
    private[snapshot] val size: Int
):

    /** Adds a field-normalization pass, scrubbing non-deterministic fields before encode and before compare.
      *
      * Accumulates: the supplied transform is applied to the config's current `Modify[A]`, so `.normalize(f1).normalize(f2)` composes
      * `f1` then `f2`.
      */
    def normalize(f: Modify[A] => Modify[A]): GoldenConfig[A] =
        new GoldenConfig[A](f(modify), sampleCount, seed, size)

    /** Sets how many generated values the spread covers (default 20). A value below 1 is rejected at the assertion boundary. */
    def sampleCount(n: Int): GoldenConfig[A] =
        new GoldenConfig[A](modify, n, seed, size)

    /** Sets the deterministic sampling seed threaded into `gen.samples` (default 0). */
    def seed(s: Long): GoldenConfig[A] =
        new GoldenConfig[A](modify, sampleCount, s, size)

    /** Sets the generator size hint threaded into `gen.samples` (default 10). */
    def size(sz: Int): GoldenConfig[A] =
        new GoldenConfig[A](modify, sampleCount, seed, sz)

end GoldenConfig

object GoldenConfig:
    /** The empty config for `A`: 20 samples, seed 0, size 10, identity normalization. */
    def apply[A]: GoldenConfig[A] = new GoldenConfig[A](Modify.apply[A], 20, 0L, 10)
