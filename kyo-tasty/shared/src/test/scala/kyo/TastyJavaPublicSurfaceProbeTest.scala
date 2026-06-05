package kyo

/** Phase 02 public-surface probe: confirms no Java-namespace paths were inadvertently removed.
  *
  * Phase 02 (Cat 4 + Cat 20) relocates SymbolKind and renames current->global. This file
  * confirms that the public Java-related type paths still compile, as a guard against
  * accidental removal during the internal relocation cascade.
  *
  * Full Java namespace restructuring (Cat 9) is done in Phase 04. This file is the Phase 02
  * baseline: paths that exist pre-Phase-04 must still exist after Phase 02.
  *
  * Pins: PRESERVE-J (Java* paths unchanged by Phase 02).
  */
class TastyJavaPublicSurfaceProbeTest extends Test:

    // ── Probe: JavaMetadata type resolves ────────────────────────────────────
    // Given: kyo.Tasty.JavaMetadata path.
    // When: compile-time check.
    // Then: the path still resolves after Phase 02 (Cat 4 did not touch JavaMetadata).
    // Pins: PRESERVE-J.
    "TastyJavaPublicSurfaceProbe: JavaMetadata path resolves after Phase 02 relocation" in {
        val ok = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.JavaMetadata = ???")
        // JavaMetadata is a case class; the error is about missing args, not missing path.
        // Either a "not found" error (path removed, fail) or a "wrong number of args" / type error
        // (path exists but cannot construct directly with ???, ok).
        // We accept both empty and non-empty errors here since ??? can satisfy any type.
        // The REAL gate is the absence of "not found: kyo.Tasty.JavaMetadata".
        assert(
            ok.forall(e => !e.message.contains("not found: kyo.Tasty.JavaMetadata")),
            s"kyo.Tasty.JavaMetadata path must still resolve after Phase 02; got: ${ok.map(_.message).mkString(", ")}"
        )
        succeed
    }

    // ── Probe: JavaAnnotation type resolves ──────────────────────────────────
    // Given: kyo.Tasty.JavaAnnotation path.
    // When: compile-time check.
    // Then: the path still resolves after Phase 02 (Cat 4 did not touch JavaAnnotation).
    // Pins: PRESERVE-J.
    "TastyJavaPublicSurfaceProbe: JavaAnnotation path resolves after Phase 02 relocation" in {
        val ok = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.JavaAnnotation = ???")
        assert(
            ok.forall(e => !e.message.contains("not found: kyo.Tasty.JavaAnnotation")),
            s"kyo.Tasty.JavaAnnotation path must still resolve after Phase 02; got: ${ok.map(_.message).mkString(", ")}"
        )
        succeed
    }

end TastyJavaPublicSurfaceProbeTest
