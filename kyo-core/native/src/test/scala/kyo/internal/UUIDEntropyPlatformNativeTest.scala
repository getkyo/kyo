package kyo.internal

import java.io.EOFException
import kyo.*
import scala.collection.mutable.ArrayBuffer

class UUIDEntropyPlatformNativeTest extends kyo.test.Test[Any]:

    final private class ScriptedSource(
        payload: Array[Byte],
        reads: Chunk[Int],
        closeFailure: Maybe[Throwable] = Absent
    ) extends UUIDEntropyPlatform.NativeSource:
        val requests         = ArrayBuffer.empty[(Int, Int)]
        var closeCalls       = 0
        private var nextRead = 0

        def read(target: Array[Byte], offset: Int, length: Int): Int =
            requests += ((offset, length))
            val result = reads(nextRead)
            nextRead += 1
            if result > 0 then
                var i = 0
                while i < result do
                    target(offset + i) = payload(offset + i)
                    i += 1
            end if
            result
        end read

        def close(): Unit =
            closeCalls += 1
            closeFailure match
                case Present(failure) => throw failure
                case Absent           => ()
        end close
    end ScriptedSource

    final private class ThrowingSource(failure: Throwable) extends UUIDEntropyPlatform.NativeSource:
        var readCalls  = 0
        var closeCalls = 0

        def read(target: Array[Byte], offset: Int, length: Int): Int =
            readCalls += 1
            throw failure

        def close(): Unit =
            closeCalls += 1
    end ThrowingSource

    final private class RecordingWindowsSource(bytes: Array[Byte], status: Int = 0) extends UUIDEntropyPlatform.WindowsSource:
        var calls      = 0
        var lastLength = -1

        def fill(target: Array[Byte]): Int =
            calls += 1
            lastLength = target.length
            if status == 0 then java.lang.System.arraycopy(bytes, 0, target, 0, target.length)
            status
        end fill
    end RecordingWindowsSource

    "Native secure entropy adapter" - {
        "live adapter returns exactly 16 bytes" in {
            UUIDEntropyPlatform.live.next16.map: bytes =>
                assert(bytes.size == 16)
        }

        "selects /dev/urandom on POSIX" in {
            val expected      = Array.tabulate[Byte](16)(i => (i * 7 + 1).toByte)
            val source        = new ScriptedSource(expected, Chunk(16))
            val windowsSource = new RecordingWindowsSource(Array.fill[Byte](16)(0))
            var openedPath    = ""
            val adapter = UUIDEntropyPlatform.forOperatingSystem(
                isWindows = false,
                openPosix = path =>
                    openedPath = path
                    source
                ,
                windowsSource = windowsSource
            )

            adapter.next16.map: actual =>
                assert(actual.is(Span.from(expected)))
                assert(openedPath == "/dev/urandom")
                assert(windowsSource.calls == 0)
        }

        "selects the Windows secure source and fills exactly 16 bytes" in {
            val expected      = Array.tabulate[Byte](16)(i => (i * 19 + 2).toByte)
            val windowsSource = new RecordingWindowsSource(expected)
            var posixOpens    = 0
            val adapter = UUIDEntropyPlatform.forOperatingSystem(
                isWindows = true,
                openPosix = _ =>
                    posixOpens += 1
                    throw new AssertionError("POSIX source opened on Windows")
                ,
                windowsSource = windowsSource
            )

            adapter.next16.map: actual =>
                assert(actual.is(Span.from(expected)))
                assert(windowsSource.calls == 1)
                assert(windowsSource.lastLength == 16)
                assert(posixOpens == 0)
        }

        "surfaces the exact Windows secure source status as a Sync panic" in {
            val windowsSource = new RecordingWindowsSource(Array.fill[Byte](16)(0), status = 0xc0000001)
            val adapter       = UUIDEntropyPlatform.fromWindowsSource(windowsSource)

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual: IllegalStateException) =>
                        assert(actual.getMessage == "BCryptGenRandom failed with NTSTATUS 0xc0000001")
                    case other => fail(s"expected BCryptGenRandom status panic, got $other")
                end match
                assert(windowsSource.calls == 1)
                assert(windowsSource.lastLength == 16)
        }

        "opens /dev/urandom and retries partial reads until exactly 16 bytes are filled" in {
            val expected   = Array.tabulate[Byte](16)(i => (i * 17 + 5).toByte)
            val source     = new ScriptedSource(expected, Chunk(3, 5, 8))
            var openedPath = ""
            val adapter = UUIDEntropyPlatform.fromNativeSource { path =>
                openedPath = path
                source
            }

            adapter.next16.map: actual =>
                assert(openedPath == "/dev/urandom")
                assert(actual.is(Span.from(expected)))
                assert(Chunk.from(source.requests) == Chunk((0, 16), (3, 13), (8, 8)))
                assert(source.closeCalls == 1)
        }

        "panics and closes the source when a read stops before 16 bytes" in {
            val source  = new ScriptedSource(Array.fill[Byte](16)(1), Chunk(4, 0))
            val adapter = UUIDEntropyPlatform.fromNativeSource(_ => source)

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual: EOFException) =>
                        assert(actual.getMessage == "Unexpected EOF from /dev/urandom after 4 of 16 bytes")
                    case other => fail(s"expected /dev/urandom EOF panic, got $other")
                end match
                assert(Chunk.from(source.requests) == Chunk((0, 16), (4, 12)))
                assert(source.closeCalls == 1)
        }

        "surfaces read failures as the exact panic and closes the source" in {
            val failure = new RuntimeException("read failed")
            val source  = new ThrowingSource(failure)
            val adapter = UUIDEntropyPlatform.fromNativeSource(_ => source)

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual) =>
                        assert(actual eq failure)
                    case other => fail(s"expected /dev/urandom read panic, got $other")
                end match
                assert(source.readCalls == 1)
                assert(source.closeCalls == 1)
        }

        "preserves the primary failure when read and close throw the same instance" in {
            val failure = new RuntimeException("read and close failed")
            val source = new UUIDEntropyPlatform.NativeSource:
                def read(target: Array[Byte], offset: Int, length: Int): Int =
                    throw failure
                def close(): Unit =
                    throw failure
            val adapter = UUIDEntropyPlatform.fromNativeSource(_ => source)

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual) =>
                        assert(actual eq failure)
                        assert(actual.getSuppressed.isEmpty)
                    case other => fail(s"expected original read panic, got $other")
        }

        "surfaces a close failure after a successful read as the exact panic" in {
            val failure = new RuntimeException("close failed")
            val source = new ScriptedSource(
                Array.tabulate[Byte](16)(_.toByte),
                Chunk(16),
                closeFailure = Maybe(failure)
            )
            val adapter = UUIDEntropyPlatform.fromNativeSource(_ => source)

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual) =>
                        assert(actual eq failure)
                    case other => fail(s"expected /dev/urandom close panic, got $other")
                end match
                assert(Chunk.from(source.requests) == Chunk((0, 16)))
                assert(source.closeCalls == 1)
        }

        "panics when /dev/urandom cannot be opened" in {
            val failure = new RuntimeException("open failed")
            val adapter = UUIDEntropyPlatform.fromNativeSource(_ => throw failure)

            Abort.run[Any](adapter.next16).map: result =>
                result match
                    case Result.Panic(actual) => assert(actual eq failure)
                    case other                => fail(s"expected /dev/urandom open panic, got $other")
        }
    }

end UUIDEntropyPlatformNativeTest
