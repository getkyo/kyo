package kyo.test.snapshot

import kyo.Modify

/** The single per-call customization point for [[SnapshotTestBase.assertSchemaSnapshot]].
  *
  * An immutable builder passed to `assertSchemaSnapshot` as a `SnapshotConfig[A] => SnapshotConfig[A]` lambda. It exists so the
  * assertion can gain new per-call customization capabilities over time without changing its signature or breaking existing call
  * sites: each new knob is an additive method here, never a new parameter on `assertSchemaSnapshot`.
  *
  * This configures a single `assertSchemaSnapshot` invocation. The serialization format is chosen separately, per suite, by the
  * [[SnapshotTestBase.snapshotCodec]] override hook, not through this builder; the two are complementary customization surfaces at
  * different scopes (per-call here, per-suite there).
  *
  * Today it carries exactly one capability, [[normalize]]. A future capability (for example a custom comparison strategy) is added
  * as a new method plus a new internal field whose default preserves the current behavior, so existing `.normalize(...)` call sites
  * keep compiling unchanged.
  *
  * Start from the empty config via the companion `SnapshotConfig[A]` (identity normalization) and compose builder methods, for
  * example `_.normalize(_.set(_.startedAt)(Instant.EPOCH))` inside the `assertSchemaSnapshot` config lambda.
  *
  * @tparam A
  *   the type being snapshotted; a `Schema[A]` is required by `assertSchemaSnapshot`
  * @see
  *   [[SnapshotTestBase.assertSchemaSnapshot]] the assertion this configures
  * @see
  *   [[SnapshotTestBase.snapshotCodec]] the separate per-suite format-selection hook
  * @see
  *   [[kyo.Modify]] the field-transform DSL that `normalize` builds on
  */
final class SnapshotConfig[A] private[snapshot] (private[snapshot] val modify: Modify[A]):

    /** Adds a field-normalization pass, scrubbing non-deterministic fields before encode and before compare.
      *
      * Accumulates: the supplied transform is applied to the config's current `Modify[A]`, so `.normalize(f1).normalize(f2)`
      * composes `f1` then `f2`.
      */
    def normalize(f: Modify[A] => Modify[A]): SnapshotConfig[A] =
        new SnapshotConfig[A](f(modify))

end SnapshotConfig

object SnapshotConfig:
    /** The empty config for `A`: identity normalization, built from the empty `Modify[A]`. */
    def apply[A]: SnapshotConfig[A] = new SnapshotConfig[A](Modify.apply[A])
