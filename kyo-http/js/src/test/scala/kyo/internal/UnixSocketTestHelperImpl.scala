package kyo.internal

import kyo.*
import scala.scalajs.js

private[kyo] trait UnixSocketTestHelperImpl extends UnixSocketTestHelper:

    // A browser page has no file system to stage a socket on and no socket to stage there, which is the same answer
    // Windows gives for its own reason: unsupported, so every socket-binding leaf cancels through `tempSocketPath`.
    override def unixSocketsSupported: Boolean = !Platform.isWindows && !Platform.isBrowser

    // Reached through process.getBuiltinModule at the call, so linking the test bundle adds no static node: import.
    private def builtin(id: String): js.Dynamic =
        PlatformJs.nodeBuiltin(
            id
        ).getOrElse(throw new IllegalStateException(s"unix socket tests need $id, which the host does not provide"))

    protected def createTempSocketPath()(using Frame): String < Sync =
        Sync.defer {
            val path   = builtin("node:path")
            val tmpDir = builtin("node:fs").mkdtempSync(path.join(builtin("node:os").tmpdir(), "kyo-unix-test-")).toString
            path.join(tmpDir, "test.sock").asInstanceOf[String]
        }

    def cleanupSocket(socketPath: String): Unit =
        try
            val fs = builtin("node:fs")
            fs.unlinkSync(socketPath)
            val dir = builtin("node:path").dirname(socketPath)
            discard(fs.rmdirSync(dir))
        catch case _: Throwable => ()
    end cleanupSocket

end UnixSocketTestHelperImpl
