package kyo.ffi

import kyo.AllowUnsafe
import kyo.ffi.internal.BufferCore
import kyo.ffi.internal.GuardCore
import kyo.internal.JvmUnsafeBuffer
import kyo.internal.UnsafeLayout

/** Tests that a buffer whose [[Buffer.close]] throws does not prevent other registered buffers from being closed.
  *
  * [[GuardCore.close]] iterates the buffer deque and swallows [[scala.util.control.NonFatal]] exceptions per-buffer. A buffer that throws
  * on `close()` must not cause subsequent buffers (earlier in LIFO order) to be skipped.
  */
class GuardCoreBufferCloseThrowsTest extends Test:

    // Create a Buffer[Byte] whose underlying UnsafeBuffer's closer throws.
    private def throwingBuffer(): (Buffer[Byte], () => Boolean) =
        import AllowUnsafe.embrace.danger
        @volatile var closerCalled = false
        val closer: () => Unit = () =>
            closerCalled = true
            throw new RuntimeException("deliberate close failure")
        val seg       = java.lang.foreign.Arena.ofShared().nn.allocate(1L)
        val buf       = new JvmUnsafeBuffer(seg, 1L, closer)
        val core      = new BufferCore(1, 1L, owned = true)
        val rawHandle = Buffer.Raw.wrap(seg)
        val buffer    = new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], core, rawHandle)
        (buffer, () => closerCalled)
    end throwingBuffer

    // Create a Buffer[Byte] that tracks whether close() was called.
    private def trackingBuffer(): (Buffer[Byte], () => Boolean) =
        import AllowUnsafe.embrace.danger
        @volatile var closerCalled = false
        val closer: () => Unit     = () => closerCalled = true
        val seg                    = java.lang.foreign.Arena.ofShared().nn.allocate(1L)
        val buf                    = new JvmUnsafeBuffer(seg, 1L, closer)
        val core                   = new BufferCore(1, 1L, owned = true)
        val rawHandle              = Buffer.Raw.wrap(seg)
        val buffer                 = new Buffer[Byte](buf, summon[UnsafeLayout[Byte]], core, rawHandle)
        (buffer, () => closerCalled)
    end trackingBuffer

    "buffer-close-throws does not prevent other buffers from being closed" in {
        val core = new GuardCore(() => (), () => ())

        val (before, beforeClosed)   = trackingBuffer()
        val (thrower, throwerClosed) = throwingBuffer()
        val (after, afterClosed)     = trackingBuffer()

        // Registration order -- LIFO, so `after` closes first, `thrower` second, `before` last.
        core.registerBuffer(before)
        core.registerBuffer(thrower)
        core.registerBuffer(after)

        // Must not throw despite thrower.close() throwing.
        val result = core.close()

        assert(result == kyo.ffi.Ffi.CloseOutcome.Clean)
        assert(afterClosed() == true)
        assert(throwerClosed() == true)
        assert(beforeClosed() == true)
    }

    "multiple throwing buffers do not prevent any buffer from receiving close()" in {
        val core = new GuardCore(() => (), () => ())

        val trackers = (0 until 3).map(_ => trackingBuffer()).toArray
        val throwers = (0 until 3).map(_ => throwingBuffer()).toArray

        // Interleave trackers and throwers.
        trackers.foreach((b, _) => core.registerBuffer(b))
        throwers.foreach((b, _) => core.registerBuffer(b))

        val result = core.close()

        assert(result == kyo.ffi.Ffi.CloseOutcome.Clean)
        trackers.foreach((_, closed) => assert(closed() == true))
        throwers.foreach((_, closed) => assert(closed() == true))
    }
end GuardCoreBufferCloseThrowsTest
