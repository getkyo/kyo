package kyo.internal

import kyo.*

private[kyo] trait UnixSocketTestHelper:
    def tempSocketPath()(using Frame): String < Sync
    def cleanupSocket(path: String): Unit
    def encodeSocketPath(path: String): String =
        java.net.URLEncoder.encode(path, "UTF-8")
    def mkUrl(socketPath: String, httpPath: String): String =
        s"http+unix://${encodeSocketPath(socketPath)}$httpPath"
end UnixSocketTestHelper
