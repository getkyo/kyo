package kyo.internal

import kyo.*

private[kyo] trait UnixSocketTestHelperImpl extends UnixSocketTestHelper:

    def tempSocketPath()(using Frame): String < Sync =
        Sync.defer {
            val tmpDir = java.io.File.createTempFile("kyo-unix-test", "")
            tmpDir.delete()
            tmpDir.mkdirs()
            new java.io.File(tmpDir, "test.sock").getAbsolutePath
        }

    def cleanupSocket(path: String): Unit =
        val f = new java.io.File(path)
        f.delete()
        val parent = f.getParentFile
        if parent != null then discard(parent.delete())
    end cleanupSocket

end UnixSocketTestHelperImpl
