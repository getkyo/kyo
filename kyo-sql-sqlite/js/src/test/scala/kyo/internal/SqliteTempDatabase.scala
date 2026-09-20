package kyo.internal

/** No throwaway database FILE on this platform, there being no filesystem, so the conformance descriptor reports itself unreachable. The
  * unit suites are unaffected: each drives one client, so they use `:memory:` and run everywhere.
  */
private[kyo] object SqliteTempDatabase:

    val available: Boolean = false

    def create(): String =
        throw new UnsupportedOperationException("this platform has no filesystem, so the SQLite conformance descriptor is unreachable")

    def delete(path: String): Unit = ()

end SqliteTempDatabase
