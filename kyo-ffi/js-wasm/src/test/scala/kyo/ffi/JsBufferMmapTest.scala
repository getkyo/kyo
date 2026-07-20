package kyo.ffi

import scala.scalajs.js.annotation.*
import scala.scalajs.js as sjs

/** Backend tests for [[Buffer.mmapReadOnly]] and [[Buffer.mmapReadWrite]] on the JS and Wasm platforms.
  *
  * On both backends mmap is a fallback that reads the entire file into an ArrayBuffer via `fs.readFileSync`.
  * Writes are in-memory only and are NOT persisted to the file. This is a documented limitation.
  *
  * The `fs`/`os`/`path` helpers reach Node through the `node:` `@JSImport` facades below rather than
  * `sjs.Dynamic.global.require`: `require` is absent under the ESModule the Wasm backend mandates, so a
  * `require`-based test throws at construction on Wasm. `Buffer` stays a `sjs.Dynamic.global` read because
  * it is a Node global present under both module kinds. The `scala.scalajs.js` package is aliased to `sjs`
  * so it does not collide with the `js` platform-selector method the test base defines.
  */
class JsBufferMmapTest extends Test:

    private def withTempFile[A](content: Array[Byte])(f: String => A): A =
        val tmpDir   = JsMmapTestOs.tmpdir()
        val filePath = JsMmapTestPath.join(tmpDir, s"kyo-mmap-js-test-${java.lang.System.currentTimeMillis()}.bin")
        try
            val nodeBuffer = sjs.Dynamic.global.Buffer.from(
                sjs.Array(content.map(_.toInt)*)
            )
            JsMmapTestFs.writeFileSync(filePath, nodeBuffer)
            f(filePath)
        finally
            try JsMmapTestFs.unlinkSync(filePath)
            catch case _: Exception => ()
        end try
    end withTempFile

    "mmapReadOnly" - {

        "reads file content correctly" in {
            val content = Array[Byte](0x41, 0x42, 0x43, 0x44)
            withTempFile(content) { filePath =>
                val buf = Buffer.mmapReadOnly(filePath)
                try
                    assert(buf.size == 4)
                    assert(buf.get(0) == 0x41.toByte)
                    assert(buf.get(1) == 0x42.toByte)
                    assert(buf.get(2) == 0x43.toByte)
                    assert(buf.get(3) == 0x44.toByte)
                finally buf.close()
                end try
            }
        }

        "with offset skips initial bytes" in {
            val content = Array[Byte](10, 20, 30, 40, 50, 60)
            withTempFile(content) { filePath =>
                val buf = Buffer.mmapReadOnly(filePath, offset = 2)
                try
                    assert(buf.size == 4)
                    assert(buf.get(0) == 30.toByte)
                    assert(buf.get(3) == 60.toByte)
                finally buf.close()
                end try
            }
        }

        "with size limits mapping" in {
            val content = (0 until 50).map(_.toByte).toArray
            withTempFile(content) { filePath =>
                val buf = Buffer.mmapReadOnly(filePath, offset = 0, size = 10)
                try
                    assert(buf.size == 10)
                    assert(buf.get(0) == 0.toByte)
                    assert(buf.get(9) == 9.toByte)
                finally buf.close()
                end try
            }
        }

        "close flips closed flag" in {
            val content = Array[Byte](1, 2, 3)
            withTempFile(content) { filePath =>
                val buf = Buffer.mmapReadOnly(filePath)
                assert(buf.get(0) == 1.toByte)
                buf.close()
                interceptThrown[IllegalStateException](buf.get(0))
            }
        }

        "non-existent file throws" in {
            interceptThrown[Exception] {
                Buffer.mmapReadOnly("/nonexistent/path/to/file.bin")
            }
        }

        "empty file maps to size 0" in {
            withTempFile(Array.empty[Byte]) { filePath =>
                val buf = Buffer.mmapReadOnly(filePath)
                try
                    assert(buf.size == 0)
                finally buf.close()
                end try
            }
        }
    }

    "mmapReadWrite" - {

        "writes are in-memory only (JS fallback semantics)" in {
            val content = Array[Byte](1, 2, 3, 4)
            withTempFile(content) { filePath =>
                val buf = Buffer.mmapReadWrite(filePath)
                (buf.set(0, 99.toByte)): Unit
                assert(buf.get(0) == 99.toByte)
                buf.close()

                // Re-read original file -- should be unchanged (the fallback does not persist writes)
                val nodeBuffer = JsMmapTestFs.readFileSync(filePath)
                assert(nodeBuffer.selectDynamic("0").asInstanceOf[Int] == 1)
                assert(nodeBuffer.selectDynamic("1").asInstanceOf[Int] == 2)
                assert(nodeBuffer.selectDynamic("2").asInstanceOf[Int] == 3)
                assert(nodeBuffer.selectDynamic("3").asInstanceOf[Int] == 4)
            }
        }
    }
end JsBufferMmapTest

/** Node `fs` members the mmap test drives, imported via `node:fs` so the test links under both module kinds. */
@sjs.native
@JSImport("node:fs", JSImport.Namespace)
private object JsMmapTestFs extends sjs.Object:
    def writeFileSync(path: String, data: sjs.Any): Unit = sjs.native
    def unlinkSync(path: String): Unit                   = sjs.native
    def readFileSync(path: String): sjs.Dynamic          = sjs.native
end JsMmapTestFs

/** Node `os` members the mmap test drives. */
@sjs.native
@JSImport("node:os", JSImport.Namespace)
private object JsMmapTestOs extends sjs.Object:
    def tmpdir(): String = sjs.native
end JsMmapTestOs

/** Node `path` members the mmap test drives. */
@sjs.native
@JSImport("node:path", JSImport.Namespace)
private object JsMmapTestPath extends sjs.Object:
    def join(parts: String*): String = sjs.native
end JsMmapTestPath
