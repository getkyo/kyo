package kyo.internal

/** No-op on JS and Wasm: every production backend registers at module load through its `@JSExportTopLevel` registration, so runtime
  * discovery already reaches all of them. The JVM and Native copies register explicitly; this one lets shared tests call `ensure()` uniformly
  * on every platform.
  */
object TestBackendRegistration:
    def ensure(): Unit = ()
end TestBackendRegistration
