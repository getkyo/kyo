package kyo.internal.sqlite

/** No throwaway database FILE on this platform, because there is no filesystem to put one in.
  *
  * The conformance descriptor needs a file rather than `:memory:`, since the battery opens a SECOND client on the same URL and every
  * connection opening `:memory:` gets its own private database. With no file to share, the descriptor reports itself unreachable here, which
  * is the same answer a server engine gives when no container daemon is running: registered, and not exercisable on this host.
  *
  * The unit suites in this module are unaffected. Each drives one client, so they use `:memory:` and run on every platform.
  */
private[sqlite] object SqliteTempDatabase:

    val available: Boolean = false

    def create(): String =
        throw new UnsupportedOperationException("this platform has no filesystem, so the SQLite conformance descriptor is unreachable")

    def delete(path: String): Unit = ()

end SqliteTempDatabase
