package kyo.internal

/** No-op for production backends on JS and Wasm: every one registers at module load through its `@JSExportTopLevel` registration, so runtime
  * discovery already reaches all of them. The JVM and Native copies register explicitly; this one lets shared tests call `ensure()` uniformly
  * on every platform.
  *
  * The [[kyo.internal.SqlTestBackend]] conformance descriptors have no classpath scan to fall back on here, so they register through
  * [[registerTestBackends]] instead.
  */
object TestBackendRegistration:
    def ensure(): Unit = registerTestBackends()

    /** Registers the [[kyo.internal.SqlTestBackend]] conformance descriptors, which a services scan cannot reach on JS and Wasm. The
      * registry's read side dedupes by class, so repeated calls do not double them.
      */
    private def registerTestBackends(): Unit =
        SqlTestBackendRegistry.register(new PostgresTestBackend())
        SqlTestBackendRegistry.register(new MysqlTestBackend())
end TestBackendRegistration
