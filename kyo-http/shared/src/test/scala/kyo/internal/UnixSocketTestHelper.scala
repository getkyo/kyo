package kyo.internal

import kyo.*

private[kyo] trait UnixSocketTestHelper:

    /** Creates a temp socket path for a unix-server test, cancelling the leaf when the platform cannot bind AF_UNIX sockets (see
      * [[unixSocketsSupported]]). Every socket-binding test acquires its path here, so the gate covers builder-based and inline servers
      * alike; pure URL-parsing tests never call it and keep running everywhere.
      */
    def tempSocketPath()(using Frame): String < Sync =
        Sync.defer {
            if !unixSocketsSupported then throw kyo.test.TestCancelled("AF_UNIX sockets unsupported on this platform")
        }.andThen(createTempSocketPath())

    protected def createTempSocketPath()(using Frame): String < Sync
    def cleanupSocket(path: String): Unit

    /** Whether this platform can bind AF_UNIX sockets in these tests. Node has no AF_UNIX support on Windows (a filesystem listen path
      * fails with EACCES), so the js-wasm helper reports false there; the JVM and Native helpers bind real sockets everywhere.
      */
    def unixSocketsSupported: Boolean = true
    def encodeSocketPath(path: String): String =
        java.net.URLEncoder.encode(path, "UTF-8")
    def mkUrl(socketPath: String, httpPath: String): String =
        s"http+unix://${encodeSocketPath(socketPath)}$httpPath"
end UnixSocketTestHelper
