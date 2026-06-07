package kyo

import kyo.internal.tasty.query.NativeFileSource

/** Tests for NativeFileSource POSIX file read.
  *
  * Writes a 100-byte file via NativeFileSource.write, then reads it back via NativeFileSource.read and verifies the returned bytes equal
  * the original. Must live in native/src/test because it references NativeFileSource directly (a Scala Native object with POSIX FFI
  * bindings).
  */
class NativeFileSourceTest extends kyo.test.Test[Any]:

    private val sourceBytes: Array[Byte] = Array.tabulate(100)(i => (i & 0xff).toByte)

    private def tmpPath(name: String): String =
        val dir = Option(java.lang.System.getenv("TMPDIR")).filter(_.nonEmpty).getOrElse("/tmp")
        s"$dir/$name"

    "NativeFileSource.read returns all bytes of a 100-byte file written via NativeFileSource.write" in {
        val path = tmpPath("kyo-native-tasty-test-read-100.bin")
        Abort.run[TastyError](
            NativeFileSource.write(path, sourceBytes).flatMap: _ =>
                NativeFileSource.read(path)
        ).map:
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
                fail(s"NativeFileSource.read failed: $e")
            case Result.Panic(t) =>
                throw t
    }

    "NativeFileSource.read on missing path returns Abort[TastyError.FileNotFound]" in {
        Abort.run[TastyError](NativeFileSource.read("/nonexistent-kyo-tasty-native-test/no.bin")).map:
            case Result.Failure(_: TastyError.FileNotFound) =>
                succeed
            case Result.Failure(e) =>
                fail(s"Expected FileNotFound but got: $e")
            case Result.Success(_) =>
                fail("Expected failure for missing path but got success")
            case Result.Panic(t) =>
                throw t
    }

end NativeFileSourceTest
