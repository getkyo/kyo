package kyo.internal

import kyo.*

private[kyo] trait UnixSocketTestHelper:
    def tempSocketPath()(using Frame): String < Sync
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
