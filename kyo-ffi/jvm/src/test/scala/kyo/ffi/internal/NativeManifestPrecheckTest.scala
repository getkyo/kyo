package kyo.ffi.internal

import kyo.ffi.Ffi
import kyo.ffi.FfiLoadError
import kyo.ffi.Test

/** Reflection-free direct-load pre-check: a binding whose native the manifest declares bundled for the current
  * platform, but whose native resource is absent, must fail `Ffi.load[T]` with a precise, catchable
  * [[FfiLoadError.LibraryNotFound]] BEFORE the generated `<T>Impl` is touched.
  *
  * Without the pre-check, `Ffi.load[T]` instantiates the impl and returns it silently; the missing native only
  * surfaces later, from deep inside the impl companion's initializer, where a throw poisons the class
  * (`ExceptionInInitializerError`, then `NoClassDefFoundError` on every later touch). This spec pins the fixed
  * behavior: `Ffi.load[T]` raises `LibraryNotFound` up front, does it again on a second load (no cached poisoned
  * state), and never constructs the impl at all.
  *
  * The same holds for a library the manifest declares for OTHER platforms only, which is what `osTargets`
  * produces: it is not bundled here, so the load must fail here, catchably, rather than passing the pre-check
  * and dying in the companion initializer at the first call. It must still succeed when the library is
  * resolvable by another route, since a native absent from the jar can be supplied by
  * `-Dkyo.ffi.<id>.path` or installed on the system.
  *
  * Fixtures, both in `META-INF/kyo-ffi/native-manifest/kyo-ffi-precheck-test.manifest`:
  * `kyo_ffi_precheck_absent` is declared for every supported `<os>-<arch>` with no native resource on the
  * classpath; `kyo_ffi_precheck_elsewhere` is declared for `solaris-sparc`, a platform no kyo build targets,
  * so the running platform is never among its declared set.
  */
class NativeManifestPrecheckTest extends Test:

    // The load cache and the construction witness are process-global; run the leaves sequentially so one leaf's
    // load / unload does not corrupt another's observation.
    override def config = super.config.sequential

    private val libId = "kyo_ffi_precheck_absent"

    private def platform: String =
        s"${NativeLoader.detectOs.tagName}-${NativeLoader.detectArch.tagName}"

    "Ffi.load raises LibraryNotFound before the impl is touched, when the manifest declares an absent native" in {
        Ffi.unload[NativeManifestPrecheckTest.PrecheckAbsentBinding]
        NativeManifestPrecheckTest.witnessConstructed = false

        val ex = intercept[FfiLoadError.LibraryNotFound](Ffi.load[NativeManifestPrecheckTest.PrecheckAbsentBinding])
        assert(ex.libraryId == libId)
        assert(ex.candidates.nonEmpty)
        val msg = ex.getMessage
        assert(msg != null)
        assert(msg.contains(libId))
        assert(msg.contains(platform))

        // The generated impl was never instantiated: the pre-check threw before FfiReflect.instantiate.
        assert(NativeManifestPrecheckTest.witnessConstructed == false)
    }

    "a second Ffi.load raises LibraryNotFound again, not a poisoned class-init error" in {
        Ffi.unload[NativeManifestPrecheckTest.PrecheckAbsentBinding]
        NativeManifestPrecheckTest.witnessConstructed = false

        // First load: LibraryNotFound (nothing cached, since the mapping function threw).
        intercept[FfiLoadError.LibraryNotFound](Ffi.load[NativeManifestPrecheckTest.PrecheckAbsentBinding])
        // Second load: the SAME catchable error, not ExceptionInInitializerError / NoClassDefFoundError.
        val ex = intercept[FfiLoadError.LibraryNotFound](Ffi.load[NativeManifestPrecheckTest.PrecheckAbsentBinding])
        assert(ex.libraryId == libId)
        assert(NativeManifestPrecheckTest.witnessConstructed == false)
    }
    "Ffi.load raises LibraryNotFound when the manifest declares the native for other platforms only" in {
        Ffi.unload[NativeManifestPrecheckTest.PrecheckElsewhereBinding]
        NativeManifestPrecheckTest.witnessElsewhereConstructed = false

        val ex = intercept[FfiLoadError.LibraryNotFound](Ffi.load[NativeManifestPrecheckTest.PrecheckElsewhereBinding])
        assert(ex.libraryId == "kyo_ffi_precheck_elsewhere")
        val msg = ex.getMessage
        assert(msg != null)
        assert(msg.contains("kyo_ffi_precheck_elsewhere"))
        // The message names both sides: where it IS bundled, and the platform it is not bundled for.
        assert(msg.contains("solaris-sparc"))
        assert(msg.contains(platform))
        assert(NativeManifestPrecheckTest.witnessElsewhereConstructed == false)
    }

    "a second load of an other-platform library raises LibraryNotFound again, not a poisoned class-init error" in {
        Ffi.unload[NativeManifestPrecheckTest.PrecheckElsewhereBinding]
        NativeManifestPrecheckTest.witnessElsewhereConstructed = false

        intercept[FfiLoadError.LibraryNotFound](Ffi.load[NativeManifestPrecheckTest.PrecheckElsewhereBinding])
        val ex = intercept[FfiLoadError.LibraryNotFound](Ffi.load[NativeManifestPrecheckTest.PrecheckElsewhereBinding])
        assert(ex.libraryId == "kyo_ffi_precheck_elsewhere")
        assert(NativeManifestPrecheckTest.witnessElsewhereConstructed == false)
    }

    "a -Dkyo.ffi.<id>.path override lets the pre-check pass for a library declared for other platforms only" in {
        // The out-of-band supply route the pre-check must not break: the native is not bundled for this
        // platform and never will be, and an operator points at one. The pre-check asks only whether the
        // override names an existing file, exactly as it does for a declared platform; opening it is the
        // load's job, so this pins the route being honoured, not that the file is a loadable library.
        Ffi.unload[NativeManifestPrecheckTest.PrecheckElsewhereBinding]
        NativeManifestPrecheckTest.witnessElsewhereConstructed = false

        val supplied = java.nio.file.Files.createTempFile("kyo_ffi_precheck_elsewhere", ".lib").nn
        val key      = "kyo.ffi.kyo_ffi_precheck_elsewhere.path"
        java.lang.System.setProperty(key, supplied.toAbsolutePath.nn.toString)
        try
            val binding = Ffi.load[NativeManifestPrecheckTest.PrecheckElsewhereBinding]
            assert(binding != null)
            assert(NativeManifestPrecheckTest.witnessElsewhereConstructed)
        finally
            val _ = java.lang.System.clearProperty(key)
            val _ = java.nio.file.Files.deleteIfExists(supplied)
            Ffi.unload[NativeManifestPrecheckTest.PrecheckElsewhereBinding]
        end try
    }
    "an override naming a missing file fails, and a bundled resource elsewhere does not vouch for it" in {
        // loadLocked uses an override unconditionally and consults no other route, so a set-but-missing
        // override is a failure even where another route would have answered. Otherwise the pre-check
        // passes and the load dies in the impl companion's initializer on the override it was given.
        Ffi.unload[NativeManifestPrecheckTest.PrecheckElsewhereBinding]
        NativeManifestPrecheckTest.witnessElsewhereConstructed = false

        val key = "kyo.ffi.kyo_ffi_precheck_elsewhere.path"
        java.lang.System.setProperty(key, "/nonexistent/kyo-ffi-precheck-elsewhere.so")
        try
            val ex = intercept[FfiLoadError.LibraryNotFound](Ffi.load[NativeManifestPrecheckTest.PrecheckElsewhereBinding])
            assert(ex.libraryId == "kyo_ffi_precheck_elsewhere")
            assert(ex.candidates.exists(_.contains("file missing")))
            assert(NativeManifestPrecheckTest.witnessElsewhereConstructed == false)
        finally
            val _ = java.lang.System.clearProperty(key)
            Ffi.unload[NativeManifestPrecheckTest.PrecheckElsewhereBinding]
        end try
    }
end NativeManifestPrecheckTest

object NativeManifestPrecheckTest:

    /** Set true by [[PrecheckAbsentBindingImpl]]'s constructor. Stays false when the pre-check prevents the impl
      * from ever being instantiated.
      */
    @volatile var witnessConstructed: Boolean = false

    /** Set true by [[PrecheckElsewhereBindingImpl]]'s constructor, for the other-platform fixture. */
    @volatile var witnessElsewhereConstructed: Boolean = false

    /** Binding trait whose native is deliberately absent from the classpath (see the fixture manifest). */
    trait PrecheckAbsentBinding extends Ffi

    /** Binding trait whose native the manifest declares for another platform only, the shape `osTargets` produces. */
    trait PrecheckElsewhereBinding extends Ffi

    /** Impl for the other-platform fixture; records construction so a leaf can prove the pre-check ran first. */
    class PrecheckElsewhereBindingImpl extends PrecheckElsewhereBinding:
        witnessElsewhereConstructed = true
    end PrecheckElsewhereBindingImpl

    /** A real generated-style impl on the classpath. Its constructor records that it ran, so the test can prove
      * the pre-check threw before FfiReflect ever instantiated it. If the pre-check were missing, `Ffi.load`
      * would construct this and return it silently.
      */
    class PrecheckAbsentBindingImpl extends PrecheckAbsentBinding:
        witnessConstructed = true
    end PrecheckAbsentBindingImpl
end NativeManifestPrecheckTest
