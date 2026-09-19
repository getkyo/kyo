package kyo.internal.doltlite

import kyo.*
import kyo.ffi.Ffi

/** Whether the DoltLite native is present on the platform running these suites.
  *
  * The engine is a compiled library, published for some platforms and not others (see
  * `kyoSqlDoltLiteOsArchTargets`), and CI runs the JVM and JS legs on windows-arm64, where it is absent by
  * declaration. Rather than skip there, each suite claims the platform with the assertion that IS true of it: that
  * reaching the engine fails, and fails as the typed unavailability rather than as a panic.
  */
private[kyo] object DoltLiteEngineProbe:

    /** Resolved once, since the answer cannot change within a run and the failing path is the expensive one.
      *
      * Loading is not enough to answer this: on the JS runtime `Ffi.load` hands back a binding whose dispatch table
      * is resolved on first use, so a missing native leaves the load silent and surfaces later as a
      * `LibraryNotFound`, or as a `TypeError` reading a method off null. The probe therefore CALLS the engine.
      * `libversionNumber` is the cheapest call that exists: no database, no handle, no allocation.
      *
      * Catches `Throwable` rather than the loader's own error, because the ways a missing native announces itself
      * are not one type: an `FfiLoadError` from the loader, a `LinkageError` from a generated companion's class
      * initialization, a `JavaScriptException` from a null dispatch table. A probe that throws is worse than one
      * that over-catches.
      */
    val available: Boolean =
        try
            import AllowUnsafe.embrace.danger
            val _ = Ffi.load[DoltLiteBindings].libversionNumber()
            true
        catch case _: Throwable => false

end DoltLiteEngineProbe
