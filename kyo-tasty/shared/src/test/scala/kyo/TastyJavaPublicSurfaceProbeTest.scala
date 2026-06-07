package kyo

/** Public-surface probe: confirms that Tasty.Java.* namespace paths resolve and that the old
  * top-level names (`Tasty.JavaMetadata`, `Tasty.JavaAnnotation`) do not.
  */
class TastyJavaPublicSurfaceProbeTest extends kyo.test.Test[Any]:

    // ── Probe: Java.Metadata type resolves ──────────────────────────────────
    "TastyJavaPublicSurfaceProbe: Java.Metadata path resolves" in {
        val errs = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.Java.Metadata = ???")
        assert(
            errs.forall(e => !e.message.contains("not found")),
            s"kyo.Tasty.Java.Metadata must resolve; got: ${errs.map(_.message).mkString(", ")}"
        )
        succeed
    }

    // ── Probe: Java.Annotation type resolves ─────────────────────────────────
    "TastyJavaPublicSurfaceProbe: Java.Annotation path resolves" in {
        val errs = compiletime.testing.typeCheckErrors("val _: kyo.Tasty.Java.Annotation = ???")
        assert(
            errs.forall(e => !e.message.contains("not found")),
            s"kyo.Tasty.Java.Annotation must resolve; got: ${errs.map(_.message).mkString(", ")}"
        )
        succeed
    }

end TastyJavaPublicSurfaceProbeTest
