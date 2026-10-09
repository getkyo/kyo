package kyo

import kyo.internal.tasty.binary.MappedByteView
import kyo.internal.tasty.snapshot.NativeMmapReader

/** Native-only mmap guard test: verifies use-after-close throws after Scope exit on the mmap arena. */
class NativeMmapReaderTest extends kyo.test.Test[Any]:

    private val tmpDir: String = Option(java.lang.System.getenv("TMPDIR")).filter(_.nonEmpty).getOrElse("/tmp")

    private def tmpPath(name: String): String = s"$tmpDir/$name"

    "NativeMmapReader: read inside open Scope succeeds and returns correct first byte" in {
        import AllowUnsafe.embrace.danger
        val path    = tmpPath("kyo-native-mmap-test-read-open.bin")
        val content = Array[Byte](0x42.toByte, 0x43.toByte, 0x44.toByte, 0x45.toByte)
        Path.run(Path(path).writeBytes(Span.from(content))).map { _ =>
            Abort.run[TastyError](
                Scope.run {
                    NativeMmapReader.init(path).map { view =>
                        val b = view.readByte()
                        assert(b == 0x42.toByte, s"Expected 0x42 but got $b")
                        succeed
                    }
                }
            ).map {
                case Result.Success(assertion) => assertion
                case Result.Failure(e)         => fail(s"Unexpected TastyError: $e")
                case Result.Panic(t)           => throw t
            }
        }
    }

    "NativeMmapReader: a path mmap cannot map fails with FileNotFound instead of handing out a view" in {
        // open(2) succeeds on a directory and mmap(2) then fails with ENODEV, returning MAP_FAILED rather than null.
        Abort.run[TastyError](Scope.run(NativeMmapReader.init(tmpDir))).map {
            case Result.Failure(TastyError.FileNotFound(message)) =>
                assert(message == s"$tmpDir: mmap failed")
            case other =>
                fail(s"Expected FileNotFound for an unmappable path, got $other")
        }
    }

    "NativeMmapReader: an empty file yields an empty view whose reads are out of bounds" in {
        import AllowUnsafe.embrace.danger
        val path = tmpPath("kyo-native-mmap-test-empty.bin")
        Path.run(Path(path).writeBytes(Span.empty[Byte])).map { _ =>
            Abort.run[TastyError](
                Scope.run {
                    NativeMmapReader.init(path).map { view =>
                        assert(view.remaining == 0L)
                        intercept[IndexOutOfBoundsException](view.peekByte(0))
                        intercept[IndexOutOfBoundsException](view.readByte())
                        succeed
                    }
                }
            ).map {
                case Result.Success(assertion) => assertion
                case other                     => fail(s"Expected an empty view, got $other")
            }
        }
    }

    "NativeMmapReader: reads outside the mapped file are out of bounds" in {
        import AllowUnsafe.embrace.danger
        val path    = tmpPath("kyo-native-mmap-test-bounds.bin")
        val content = Array[Byte](0x11.toByte, 0x22.toByte, 0x33.toByte, 0x44.toByte)
        Path.run(Path(path).writeBytes(Span.from(content))).map { _ =>
            Abort.run[TastyError](
                Scope.run {
                    NativeMmapReader.init(path).map { view =>
                        assert(view.peekByte(3) == 0x44.toByte)
                        intercept[IndexOutOfBoundsException](view.peekByte(4))
                        intercept[IndexOutOfBoundsException](view.peekByte(-1))
                        intercept[IndexOutOfBoundsException](view.subView(2, 4).peekByte(4))
                        view.goto(3)
                        assert(view.readByte() == 0x44.toByte)
                        intercept[IndexOutOfBoundsException](view.readByte())
                        succeed
                    }
                }
            ).map {
                case Result.Success(assertion) => assertion
                case other                     => fail(s"Expected a view over the file, got $other")
            }
        }
    }

    "NativeMmapReader: read after Scope closes raises IllegalStateException with 'mmap arena closed'" in {
        import AllowUnsafe.embrace.danger
        val path    = tmpPath("kyo-native-mmap-test-scope-exit.bin")
        val content = Array[Byte](0x01.toByte, 0x02.toByte, 0x03.toByte, 0x04.toByte)
        // Capture the view reference outside the scope so we can read after the scope exits.
        var capturedView: MappedByteView = null
        Path.run(Path(path).writeBytes(Span.from(content))).map { _ =>
            Abort.run[TastyError](
                Scope.run {
                    NativeMmapReader.init(path).map { view =>
                        capturedView = view
                        succeed
                    }
                }
            ).map { scopeResult =>
                scopeResult match
                    case Result.Failure(e) => fail(s"Unexpected TastyError: $e")
                    case Result.Panic(t)   => throw t
                    case Result.Success(_) =>
                        // Scope.run has exited: the finalizer ran, setting closed = true.
                        assert(capturedView != null, "Expected view to be non-null after scope")
                        val ex = intercept[IllegalStateException] {
                            capturedView.readByte()
                        }
                        assert(
                            ex.getMessage == "mmap arena closed",
                            s"Expected 'mmap arena closed' but got '${ex.getMessage}'"
                        )
                        succeed
            }
        }
    }

end NativeMmapReaderTest
