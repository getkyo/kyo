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
      * Catches `Throwable` rather than the loader's own error: a native missing at class-initialization time
      * surfaces as a `LinkageError` from the generated companion, which is not an `FfiLoadError` and would escape a
      * narrower catch. A probe that throws is worse than one that over-catches.
      */
    val available: Boolean =
        try
            import AllowUnsafe.embrace.danger
            val _ = Ffi.load[DoltLiteBindings]
            true
        catch case _: Throwable => false

end DoltLiteEngineProbe
