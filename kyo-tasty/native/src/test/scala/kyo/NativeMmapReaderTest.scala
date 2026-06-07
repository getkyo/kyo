package kyo

import kyo.internal.tasty.binary.MappedByteView
import kyo.internal.tasty.query.NativeFileSource
import kyo.internal.tasty.snapshot.NativeMmapReader

/** Tests for NativeMmapReader: read behavior inside and after a Scope.
  *
  * Verifies that reading inside an open Scope succeeds, and that after the Scope closes the AtomicBoolean
  * flag prevents further reads by throwing IllegalStateException("mmap arena closed") rather than allowing
  * a use-after-munmap access.
  *
  * Must live in native/src/test because it imports NativeMmapReader and MappedByteView (Scala Native objects
  * with POSIX FFI bindings).
  */
class NativeMmapReaderTest extends kyo.test.Test[Any]:

    private def tmpPath(name: String): String =
        val dir = Option(java.lang.System.getenv("TMPDIR")).filter(_.nonEmpty).getOrElse("/tmp")
        s"$dir/$name"

    "NativeMmapReader: read inside open Scope succeeds and returns correct first byte" in {
        import AllowUnsafe.embrace.danger
        val path    = tmpPath("kyo-native-mmap-test-read-open.bin")
        val content = Array[Byte](0x42.toByte, 0x43.toByte, 0x44.toByte, 0x45.toByte)
        Abort.run[TastyError](
            NativeFileSource.write(path, content).flatMap: _ =>
                Scope.run:
                    NativeMmapReader.init(path).map: view =>
                        val b = view.readByte()
                        assert(b == 0x42.toByte, s"Expected 0x42 but got $b")
                        succeed
        ).map:
            case Result.Success(assertion) => assertion
            case Result.Failure(e)         => fail(s"Unexpected TastyError: $e")
            case Result.Panic(t)           => throw t
    }

    "NativeMmapReader: read after Scope closes raises IllegalStateException with 'mmap arena closed'" in {
        import AllowUnsafe.embrace.danger
        val path    = tmpPath("kyo-native-mmap-test-scope-exit.bin")
        val content = Array[Byte](0x01.toByte, 0x02.toByte, 0x03.toByte, 0x04.toByte)
        // Capture the view reference outside the scope so we can read after the scope exits.
        var capturedView: MappedByteView = null
        Abort.run[TastyError](
            NativeFileSource.write(path, content).flatMap: _ =>
                Scope.run:
                    NativeMmapReader.init(path).map: view =>
                        capturedView = view
                        succeed
        ).map: scopeResult =>
            scopeResult match
                case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)   => throw t
                case Result.Success(_) =>
                    // Scope.run has exited: the finalizer ran, setting closed = true.
                    assert(capturedView != null, "Expected view to be non-null after scope")
                    val ex = intercept[IllegalStateException]:
                        capturedView.readByte()
                    assert(
                        ex.getMessage == "mmap arena closed",
                        s"Expected 'mmap arena closed' but got '${ex.getMessage}'"
                    )
                    succeed
    }

end NativeMmapReaderTest
