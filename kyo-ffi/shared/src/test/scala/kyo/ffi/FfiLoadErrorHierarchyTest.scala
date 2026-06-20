package kyo.ffi

import kyo.Chunk

/** Validates the sealed [[FfiLoadError]] hierarchy that unifies every `Ffi.load[T]` failure under a single catch surface.
  *
  * Scenarios cover: (a) the not-found-library path throws [[FfiLoadError.LibraryNotFound]] with `libraryId` + non-empty `candidates`, (b)
  * [[FfiLoadError.Unsupported]] is caught by `catch FfiLoadError`, (c) [[FfiLoadError.AbiMismatch]] is a subtype of [[FfiLoadError]], and (d)
  * a binding trait without a generated impl surfaces as [[FfiLoadError.ImplNotFound]] with `traitFqcn` populated. The 32-bit-host scenario
  * from the plan is intentionally omitted, it requires a 32-bit JVM to exercise reliably and is already covered indirectly by per-platform
  * `*PlatformCheckTest` suites.
  */
class FfiLoadErrorHierarchyTest extends Test:

    "Ffi.load on a trait with no generated impl throws FfiLoadError.ImplNotFound with the binding's FQCN" in {
        val ex = intercept[FfiLoadError.ImplNotFound](Ffi.load[FfiLoadErrorHierarchyTest.BogusBindings])
        // The FQCN format for nested traits uses `$` as the inner-class separator on the JVM classpath,
        // and the same string is what FfiReflect computes from `cls.getName`.
        assert(ex.traitFqcn == "kyo.ffi.FfiLoadErrorHierarchyTest$BogusBindings")
    }

    "FfiLoadError.ImplNotFound is a subtype of FfiLoadError so one catch handles all load failures" in {
        val ex = intercept[FfiLoadError](Ffi.load[FfiLoadErrorHierarchyTest.BogusBindings])
        assert(ex.isInstanceOf[FfiLoadError.ImplNotFound])
    }

    "FfiLoadError.Unsupported is caught by `catch FfiLoadError` (unified surface)" in {
        val thrown: Throwable =
            try throw new FfiLoadError.Unsupported("synthetic unsupported")
            catch case e: FfiLoadError => e
        assert(thrown.isInstanceOf[FfiLoadError.Unsupported])
    }

    "FfiLoadError.AbiMismatch is a subtype of FfiLoadError" in {
        val ex = new FfiLoadError.AbiMismatch("16", "9")
        assert(ex.isInstanceOf[FfiLoadError])
    }

    "FfiLoadError.LibraryNotFound exposes libraryId and a non-empty candidates list" in {
        // Synthesised directly, exercising NativeLoader.load is a platform-specific concern covered by
        // the per-platform loader tests; this scenario validates the structural contract of the case class.
        val ex = new FfiLoadError.LibraryNotFound(
            libraryId = "this-library-does-not-exist-zzz",
            candidates = Chunk(
                "resource path: /META-INF/native/linux-x86_64/libthis-library-does-not-exist-zzz.so",
                "system library: this-library-does-not-exist-zzz"
            ),
            cause = null
        )
        assert(ex.libraryId == "this-library-does-not-exist-zzz")
        assert(ex.candidates.nonEmpty)
        assert(ex.isInstanceOf[FfiLoadError])
    }
end FfiLoadErrorHierarchyTest

object FfiLoadErrorHierarchyTest:
    /** Synthetic binding trait with no generated impl, used to provoke the [[FfiLoadError.ImplNotFound]] error path. */
    trait BogusBindings extends Ffi
end FfiLoadErrorHierarchyTest
