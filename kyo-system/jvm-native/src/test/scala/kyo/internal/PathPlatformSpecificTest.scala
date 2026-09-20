package kyo.internal

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kyo.*

/** The write loops' zero-progress guard, on both platforms that share the NIO handles.
  *
  * A short write is retried from where it stopped; a write that takes no bytes cannot make progress,
  * so it ends the loop as a failure. Each leaf asserts the channel was asked exactly once, which is
  * what proves the retry is gone.
  */
class PathPlatformSpecificTest extends kyo.test.Test[Any]:

    "a write handle fails a write that reports no progress" in {
        val channel = StallingFileChannel()
        val handle  = new NioWriteHandle(channel, Path("stalled-write.bin"))
        Sync.Unsafe.defer(handle.writeBytes(Chunk[Byte](1, 2, 3))).map { result =>
            assert(channel.writeCalls == 1)
            result match
                case Result.Failure(error: FileWriteStalledException) => assert(error.remaining == 3.bytes)
                case other                                            => fail(s"expected FileWriteStalledException, got $other")
        }
    }

    "a raw channel fails a positioned write that reports no progress" in {
        val channel = StallingFileChannel()
        val handle  = new NioRawChannel(channel, Path("stalled-write-at.bin"))
        Sync.Unsafe.defer(handle.writeAt(0L, Array[Byte](1, 2, 3))).map { result =>
            assert(channel.positionedWriteCalls == 1)
            result match
                case Result.Failure(error: FileWriteStalledException) => assert(error.remaining == 3.bytes)
                case other                                            => fail(s"expected FileWriteStalledException, got $other")
        }
    }

end PathPlatformSpecificTest

/** A channel whose writes take no bytes.
  *
  * Both writes throw once past a small call bound rather than returning zero forever: a regression of
  * the guard then surfaces as a failed call-count assertion instead of hanging the suite. Only the two
  * writes are implemented, and without `override`, because the abstract member set of `FileChannel`
  * differs between the JVM and Native class libraries and these tests call nothing else.
  */
final private class StallingFileChannel extends FileChannel:

    var writeCalls           = 0
    var positionedWriteCalls = 0

    private def stall(calls: Int): Int =
        if calls > 4 then throw AssertionError("write loop retried a write that reported no progress")
        else 0

    def write(src: ByteBuffer): Int =
        writeCalls += 1
        stall(writeCalls)

    def write(src: ByteBuffer, position: Long): Int =
        positionedWriteCalls += 1
        stall(positionedWriteCalls)

    private def unused: Nothing = throw new UnsupportedOperationException("not used by these tests")

    def read(dst: ByteBuffer): Int                                                                   = unused
    def read(dsts: Array[ByteBuffer], offset: Int, length: Int): Long                                = unused
    def read(dst: ByteBuffer, position: Long): Int                                                   = unused
    def write(srcs: Array[ByteBuffer], offset: Int, length: Int): Long                               = unused
    def position(): Long                                                                             = unused
    def position(newPosition: Long): FileChannel                                                     = unused
    def size(): Long                                                                                 = unused
    def truncate(size: Long): FileChannel                                                            = unused
    def force(metaData: Boolean): Unit                                                               = unused
    def transferTo(position: Long, count: Long, target: java.nio.channels.WritableByteChannel): Long = unused
    def transferFrom(src: java.nio.channels.ReadableByteChannel, position: Long, count: Long): Long  = unused
    def map(mode: FileChannel.MapMode, position: Long, size: Long): java.nio.MappedByteBuffer        = unused
    def lock(position: Long, size: Long, shared: Boolean): java.nio.channels.FileLock                = unused
    def tryLock(position: Long, size: Long, shared: Boolean): java.nio.channels.FileLock             = unused
    protected def implCloseChannel(): Unit                                                           = ()

end StallingFileChannel
