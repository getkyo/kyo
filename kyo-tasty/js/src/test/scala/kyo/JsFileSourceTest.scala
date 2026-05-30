package kyo

import kyo.internal.tasty.query.JsFileSource
import scala.scalajs.js
import scala.scalajs.js.Dynamic.global as jsGlobal

/** T5 parity test for JsFileSource on JS.
  *
  * Constructs a 100-byte file via Node.js fs.writeFileSync, then reads it back via JsFileSource.read(path) and verifies the returned bytes
  * equal the original. Pins T5 (JS-only path).
  *
  * Must live in js/src/test because it imports scala.scalajs.js.
  */
class JsFileSourceTest extends Test:

    private val sourceBytes: Array[Byte] = Array.tabulate(100)(i => (i & 0xff).toByte)

    "JsFileSource.read returns all bytes of a 100-byte file written by Node.js fs" in run {
        Sync.defer:
            val os      = jsGlobal.require("os").asInstanceOf[js.Dynamic]
            val path0   = jsGlobal.require("path").asInstanceOf[js.Dynamic]
            val fs      = jsGlobal.require("fs").asInstanceOf[js.Dynamic]
            val tmpDir  = os.tmpdir().asInstanceOf[String]
            val tmpPath = path0.join(tmpDir, "kyo-jstasty-test-read-100.bin").asInstanceOf[String]
            val int8Arr = new js.typedarray.Int8Array(sourceBytes.length)
            var w       = 0
            while w < sourceBytes.length do
                int8Arr(w) = sourceBytes(w)
                w += 1
            val _ = fs.writeFileSync(tmpPath, int8Arr)
            tmpPath
        .flatMap: tmpPath =>
            Abort.run[TastyError](JsFileSource.read(tmpPath)).map:
                case Result.Success(bytes) =>
                    assert(
                        bytes.length == 100,
                        s"Expected 100 bytes but got ${bytes.length}"
                    )
                    assert(
                        bytes.sameElements(sourceBytes),
                        s"Bytes mismatch: first 10 expected=${sourceBytes.take(10).toSeq} got=${bytes.take(10).toSeq}"
                    )
                case Result.Failure(e) =>
                    fail(s"JsFileSource.read failed: $e")
                case Result.Panic(t) =>
                    throw t
    }

    "JsFileSource.read on missing path returns Abort[TastyError.FileNotFound]" in run {
        Abort.run[TastyError](JsFileSource.read("/nonexistent-kyo-tasty-test-path/no.bin")).map:
            case Result.Failure(_: TastyError.FileNotFound) =>
                succeed
            case Result.Failure(e) =>
                fail(s"Expected FileNotFound but got: $e")
            case Result.Success(_) =>
                fail("Expected failure for missing path but got success")
            case Result.Panic(t) =>
                throw t
    }

end JsFileSourceTest
