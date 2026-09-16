package kyo.ffi.internal

import kyo.Maybe.Absent
import kyo.Maybe.Present

/** The part of `Ffi.load` every platform shares: the cache of loaded impls and the manifest pre-check that runs before an impl is built.
  *
  * Expansions of `Ffi.load` land in the caller's package, so the members they call are public.
  */
object FfiLoadCore:

    /** The loaded impl of each binding trait, keyed by the trait's class. */
    val cache = new java.util.concurrent.ConcurrentHashMap[Class[?], AnyRef]()

    /** Checks, before the impl of `traitFqn` is touched, that the native library the manifest maps it to is accounted for on this platform.
      *
      * Reading the trait's name does not initialize the generated `<T>Impl`, so a missing bundled native raises a precise, catchable
      * `FfiLoadError.LibraryNotFound` (or `AbiMismatch` on a runtime-version shortfall) from `Ffi.load[T]`, instead of the generated
      * companion's initializer throwing and poisoning the class (`ExceptionInInitializerError`, then `NoClassDefFoundError` on every later
      * touch). A trait with no manifest entry (an unmigrated module, a system library such as `c`, or a runtime with no manifests) skips the
      * check.
      */
    def precheck(traitFqn: String): Unit =
        NativeManifest.libraryIdFor(traitFqn) match
            case Present(id) =>
                NativeManifest.entryFor(id) match
                    case Present(entry) =>
                        AbiCheck.verifyRuntimeFloor(traitFqn, entry.minRuntime)
                        NativeManifestPlatform.assertBundledPresent(id, entry.platforms)
                    case Absent => ()
            case Absent => ()

end FfiLoadCore
