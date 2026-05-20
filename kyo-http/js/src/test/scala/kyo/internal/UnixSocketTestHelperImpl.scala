package kyo.internal

import kyo.*
import scala.scalajs.js

private[kyo] trait UnixSocketTestHelperImpl extends UnixSocketTestHelper:

    private val os   = js.Dynamic.global.require("os")
    private val path = js.Dynamic.global.require("path")
    private val fs   = js.Dynamic.global.require("fs")

    def tempSocketPath()(using Frame): String < Sync =
        Sync.defer {
            val tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "kyo-unix-test-")).toString
            path.join(tmpDir, "test.sock").toString
        }

    def cleanupSocket(socketPath: String): Unit =
        try
            fs.unlinkSync(socketPath)
            val dir = path.dirname(socketPath).toString
            discard(fs.rmdirSync(dir))
        catch case _: Throwable => ()
    end cleanupSocket

end UnixSocketTestHelperImpl
