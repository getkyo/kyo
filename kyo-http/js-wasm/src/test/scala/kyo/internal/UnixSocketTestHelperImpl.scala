package kyo.internal

import kyo.*
import scala.scalajs.js

private[kyo] trait UnixSocketTestHelperImpl extends UnixSocketTestHelper:

    override def unixSocketsSupported: Boolean = !Platform.isWindows

    private val fs = HttpFs.asInstanceOf[js.Dynamic]

    protected def createTempSocketPath()(using Frame): String < Sync =
        Sync.defer {
            val tmpDir = fs.mkdtempSync(HttpNodePath.join(HttpOs.tmpdir(), "kyo-unix-test-")).toString
            HttpNodePath.join(tmpDir, "test.sock")
        }

    def cleanupSocket(socketPath: String): Unit =
        try
            fs.unlinkSync(socketPath)
            val dir = HttpNodePath.dirname(socketPath)
            discard(fs.rmdirSync(dir))
        catch case _: Throwable => ()
    end cleanupSocket

end UnixSocketTestHelperImpl
