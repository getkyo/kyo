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
  * Fixture: the manifest resource `META-INF/kyo-ffi/native-manifest/kyo-ffi-precheck-test.manifest` maps the
  * trait FQN below to library id `kyo_ffi_precheck_absent`, declared bundled for every supported `<os>-<arch>`
  * but with no `META-INF/native/.../libkyo_ffi_precheck_absent.*` resource on the classpath.
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
end NativeManifestPrecheckTest

object NativeManifestPrecheckTest:

    /** Set true by [[PrecheckAbsentBindingImpl]]'s constructor. Stays false when the pre-check prevents the impl
      * from ever being instantiated.
      */
    @volatile var witnessConstructed: Boolean = false

    /** Binding trait whose native is deliberately absent from the classpath (see the fixture manifest). */
    trait PrecheckAbsentBinding extends Ffi

    /** A real generated-style impl on the classpath. Its constructor records that it ran, so the test can prove
      * the pre-check threw before FfiReflect ever instantiated it. If the pre-check were missing, `Ffi.load`
      * would construct this and return it silently.
      */
    class PrecheckAbsentBindingImpl extends PrecheckAbsentBinding:
        witnessConstructed = true
    end PrecheckAbsentBindingImpl
end NativeManifestPrecheckTest
