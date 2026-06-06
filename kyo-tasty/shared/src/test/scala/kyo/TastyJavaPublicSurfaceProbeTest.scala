package kyo

/** public-surface probe: confirms the new Java namespace paths resolve and the old pre-Phase-04 paths do not.
  *
  * (Cat 9) moves all Java-namespace types under `Tasty.Java.*`. The old top-level names
  * (`Tasty.JavaMetadata`, `Tasty.JavaAnnotation`) are removed. This file probes the new paths.
  *
  * Negative coverage (old paths no longer resolve) lives in TastyJavaNamespaceTest (leaf-2).
  *
  * Supersedes the probes for `kyo.Tasty.JavaMetadata` and `kyo.Tasty.JavaAnnotation` which
  * asserted the pre-Phase-04 paths; those probes were retired here because removes those paths.
  */
class TastyJavaPublicSurfaceProbeTest extends Test:

    // ── Probe: Java.Metadata type resolves ──────────────────────────────────
    // Given: kyo.Tasty.Java.Metadata path introduced in.
    // When: compile-time check.
    // Then: the path resolves (no "not found" error).
    "TastyJavaPublicSurfaceProbe: Java.Metadata path resolves" in {
        val errs = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.Java.Metadata = ???")
        assert(
            errs.forall(e => !e.message.contains("not found")),
            s"kyo.Tasty.Java.Metadata must resolve; got: ${errs.map(_.message).mkString(", ")}"
        )
        succeed
    }

    // ── Probe: Java.Annotation type resolves ─────────────────────────────────
    // Given: kyo.Tasty.Java.Annotation path introduced in.
    // When: compile-time check.
    // Then: the path resolves (no "not found" error).
    "TastyJavaPublicSurfaceProbe: Java.Annotation path resolves" in {
        val errs = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.Java.Annotation = ???")
        assert(
            errs.forall(e => !e.message.contains("not found")),
            s"kyo.Tasty.Java.Annotation must resolve; got: ${errs.map(_.message).mkString(", ")}"
        )
        succeed
    }

end TastyJavaPublicSurfaceProbeTest
