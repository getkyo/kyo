package kyo

import kyo.internal.tasty.binary.MappedByteView
import kyo.internal.tasty.query.NativeFileSource
import kyo.internal.tasty.snapshot.NativeMmapReader

/** T5 Native parity tests for NativeMmapReader signal safety.
  *
  * Tests:
  *   1. Read inside an open Scope succeeds.
  *   2. After Scope.run exits, the Scope finalizer marks the arena closed; a subsequent read raises IllegalStateException("mmap arena
  *      closed") rather than a SIGSEGV.
  *
  * The full concurrent-unmap variant (harness triggers munmap while reader fiber is mid-read) is deferred: Scala Native 0.5 multithreading
  * support does not yet provide safe ways to race a munmap against an active read without risking a genuine SIGSEGV. The simpler
  * "close-after-scope-exit" variant covers the closed-flag guard code path and is sufficient to pin T5.
  *
  * Must live in native/src/test because it imports NativeMmapReader and MappedByteView (Scala Native objects with POSIX FFI bindings).
  *
  * Pins T5 (Native-only path).
  */
class NativeMmapReaderTest extends Test:

    private def tmpPath(name: String): String =
        val dir = Option(java.lang.System.getenv("TMPDIR")).filter(_.nonEmpty).getOrElse("/tmp")
        s"$dir/$name"

    "NativeMmapReader: read inside open Scope succeeds and returns correct first byte" in run {
        // §839 case 3; direct MappedByteView readByte test in mmap context.
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

    "NativeMmapReader: read after Scope closes raises IllegalStateException with 'mmap arena closed'" in run {
        // §839 case 3; direct MappedByteView closed-scope test.
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
