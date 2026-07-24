package kyo.internal

import java.io.EOFException
import java.io.FileInputStream
import kyo.*
import scala.scalanative.runtime.ByteArray
import scala.scalanative.unsafe.*

private[kyo] trait UUIDEntropyPlatformPlatformSpecific:

    private val SourcePath = "/dev/urandom"

    private[kyo] trait NativeSource:
        def read(target: Array[Byte], offset: Int, length: Int): Int
        def close(): Unit
    end NativeSource

    private[kyo] trait WindowsSource:
        def fill(target: Array[Byte]): Int
    end WindowsSource

    val live: UUIDEntropyPlatform =
        forOperatingSystem(
            Platform.isWindows,
            openPosix,
            WindowsSystemSource
        )

    private def openPosix(path: String): NativeSource =
        val input = new FileInputStream(path)
        new NativeSource:
            def read(target: Array[Byte], offset: Int, length: Int): Int =
                input.read(target, offset, length)
            def close(): Unit =
                input.close()
        end new
    end openPosix

    private object WindowsSystemSource extends WindowsSource:
        def fill(target: Array[Byte]): Int =
            // Unsafe: the array remains strongly reachable and is not mutated by Scala while the synchronous native call fills it.
            UUIDEntropyWindows.kyo_uuid_bcrypt_gen_random(
                target.asInstanceOf[ByteArray].at(0),
                target.length
            )
    end WindowsSystemSource

    private[kyo] def forOperatingSystem(
        isWindows: Boolean,
        openPosix: String => NativeSource,
        windowsSource: WindowsSource
    ): UUIDEntropyPlatform =
        if isWindows then fromWindowsSource(windowsSource)
        else fromNativeSource(openPosix)

    private[kyo] def fromWindowsSource(source: WindowsSource): UUIDEntropyPlatform =
        new UUIDEntropyPlatform:
            def next16(using Frame): Span[Byte] < Sync =
                Sync.defer:
                    val bytes  = new Array[Byte](16)
                    val status = source.fill(bytes)
                    if status != 0 then
                        throw new IllegalStateException(
                            s"BCryptGenRandom failed with NTSTATUS 0x${java.lang.Integer.toHexString(status)}"
                        )
                    end if
                    Span.fromUnsafe(bytes)

    private[kyo] def fromNativeSource(open: String => NativeSource): UUIDEntropyPlatform =
        new UUIDEntropyPlatform:
            def next16(using Frame): Span[Byte] < Sync =
                Sync.defer:
                    val source                    = open(SourcePath)
                    var primary: Maybe[Throwable] = Absent
                    try
                        val bytes = new Array[Byte](16)
                        var read  = 0
                        while read < 16 do
                            val count = source.read(bytes, read, 16 - read)
                            if count <= 0 then
                                throw new EOFException(s"Unexpected EOF from $SourcePath after $read of 16 bytes")
                            read += count
                        end while
                        Span.fromUnsafe(bytes)
                    catch
                        case failure: Throwable =>
                            primary = Maybe(failure)
                            throw failure
                    finally
                        try source.close()
                        catch
                            case closeFailure: Throwable =>
                                primary match
                                    case Present(failure) =>
                                        if failure ne closeFailure then failure.addSuppressed(closeFailure)
                                    case Absent => throw closeFailure
                    end try
end UUIDEntropyPlatformPlatformSpecific

@extern
private object UUIDEntropyWindows:
    def kyo_uuid_bcrypt_gen_random(target: Ptr[Byte], length: CInt): CInt = extern
end UUIDEntropyWindows
