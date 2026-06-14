package kyo.ffi.it

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Integration test for macOS/BSD kqueue via kyo-ffi.
  *
  * Exercises the full lifecycle: `kqueue` -> `kevent` (register) -> `kevent` (poll) -> `close`. Uses a pipe pair to trigger `EVFILT_READ`
  * events.
  *
  * `kevent`, `write`, `read`, and `close` are `@Ffi.blocking`, so the generator returns a `Fiber.Unsafe`; the call sites await it with
  * `.safe.get` and the test bodies are threaded through the suite's async `run`. The non-blocking calls (`kqueue`, `pipe`) return plain
  * values and only need `AllowUnsafe` in scope.
  *
  * All tests skip with `cancel` on non-macOS/BSD hosts where kqueue is unavailable.
  */
class KqueueTest extends ItTestBase:

    private val isMacOrBsd: Boolean =
        val os = java.lang.System.getProperty("os.name", "").toLowerCase
        os.contains("mac") || os.contains("bsd")

    private def assumeKqueue(): Unit =
        if !isMacOrBsd then cancel("kqueue is macOS/BSD-only")

    // struct kevent layout (macOS x86_64 / arm64):
    //   uintptr_t ident;    // offset 0, 8 bytes
    //   int16_t   filter;   // offset 8, 2 bytes
    //   uint16_t  flags;    // offset 10, 2 bytes
    //   uint32_t  fflags;   // offset 12, 4 bytes
    //   intptr_t  data;     // offset 16, 8 bytes
    //   void*     udata;    // offset 24, 8 bytes
    // Total: 32 bytes
    private val KEVENT_SIZE = 32

    // struct timespec layout:
    //   time_t tv_sec;      // offset 0, 8 bytes (on 64-bit)
    //   long   tv_nsec;     // offset 8, 8 bytes
    // Total: 16 bytes
    private val TIMESPEC_SIZE = 16

    private val EVFILT_READ: Short = -1
    private val EV_ADD: Short      = 0x0001
    private val EV_ENABLE: Short   = 0x0004
    private val EV_DELETE: Short   = 0x0002

    /** Write a `struct kevent` into `buf` at the given byte offset. Little-endian layout. */
    private def writeKevent(
        buf: Buffer[Byte],
        offset: Int,
        ident: Long,
        filter: Short,
        flags: Short,
        fflags: Int,
        data: Long,
        udata: Long
    ): Unit =
        // ident (uintptr_t, 8 bytes LE)
        var i = 0
        while i < 8 do
            buf.set(offset + i, ((ident >> (i * 8)) & 0xff).toByte)
            i += 1
        // filter (int16_t, 2 bytes LE)
        buf.set(offset + 8, (filter & 0xff).toByte)
        buf.set(offset + 9, ((filter >> 8) & 0xff).toByte)
        // flags (uint16_t, 2 bytes LE)
        buf.set(offset + 10, (flags & 0xff).toByte)
        buf.set(offset + 11, ((flags >> 8) & 0xff).toByte)
        // fflags (uint32_t, 4 bytes LE)
        buf.set(offset + 12, (fflags & 0xff).toByte)
        buf.set(offset + 13, ((fflags >> 8) & 0xff).toByte)
        buf.set(offset + 14, ((fflags >> 16) & 0xff).toByte)
        buf.set(offset + 15, ((fflags >> 24) & 0xff).toByte)
        // data (intptr_t, 8 bytes LE)
        i = 0
        while i < 8 do
            buf.set(offset + 16 + i, ((data >> (i * 8)) & 0xff).toByte)
            i += 1
        // udata (void*, 8 bytes LE)
        i = 0
        while i < 8 do
            buf.set(offset + 24 + i, ((udata >> (i * 8)) & 0xff).toByte)
            i += 1
    end writeKevent

    /** Write a `struct timespec` into `buf` at the given byte offset. */
    private def writeTimespec(buf: Buffer[Byte], offset: Int, sec: Long, nsec: Long): Unit =
        var i = 0
        while i < 8 do
            buf.set(offset + i, ((sec >> (i * 8)) & 0xff).toByte)
            i += 1
        i = 0
        while i < 8 do
            buf.set(offset + 8 + i, ((nsec >> (i * 8)) & 0xff).toByte)
            i += 1
    end writeTimespec

    /** Read the `ident` field (uintptr_t, 8 bytes) from a `struct kevent` at the given offset. */
    private def readIdent(buf: Buffer[Byte], offset: Int): Long =
        var v = 0L
        var i = 0
        while i < 8 do
            v |= (buf.get(offset + i).toLong & 0xff) << (i * 8)
            i += 1
        v
    end readIdent

    /** Read the `filter` field (int16_t, 2 bytes) from a `struct kevent` at the given offset. */
    private def readFilter(buf: Buffer[Byte], offset: Int): Short =
        val lo = buf.get(offset + 8) & 0xff
        val hi = buf.get(offset + 9) & 0xff
        ((hi << 8) | lo).toShort
    end readFilter

    /** Read the `data` field (intptr_t, 8 bytes) from a `struct kevent` at the given offset. */
    private def readData(buf: Buffer[Byte], offset: Int): Long =
        var v = 0L
        var i = 0
        while i < 8 do
            v |= (buf.get(offset + 16 + i).toLong & 0xff) << (i * 8)
            i += 1
        v
    end readData

    /** Create a zero-duration timespec for non-blocking kevent polls. */
    private def zeroTimeout(): Buffer[Byte] =
        val ts = Buffer.alloc[Byte](TIMESPEC_SIZE)
        writeTimespec(ts, 0, 0L, 0L)
        ts
    end zeroTimeout

    /** Create a timespec with the given millisecond timeout. */
    private def msTimeout(ms: Long): Buffer[Byte] =
        val ts = Buffer.alloc[Byte](TIMESPEC_SIZE)
        writeTimespec(ts, 0, ms / 1000, (ms % 1000) * 1000000L)
        ts
    end msTimeout

    "kqueue" - {
        "create + close lifecycle" in {
            assumeKqueue()
            val b  = Ffi.load[KqueueBindings]
            val kq = b.kqueue()
            assert(kq >= 0)
            b.close(kq).safe.get.map(rc => assert(rc == 0))
        }

        "kevent times out with no registered fds" in {
            assumeKqueue()
            val b  = Ffi.load[KqueueBindings]
            val kq = b.kqueue()
            assert(kq >= 0)

            val resultBuf = Buffer.alloc[Byte](KEVENT_SIZE)
            val emptyBuf  = Buffer.alloc[Byte](1) // dummy changelist (nchanges=0)
            val ts        = msTimeout(10)
            for
                n <- b.kevent(kq, emptyBuf, 0, resultBuf, 1, ts).safe.get
                _ <- b.close(kq).safe.get
            yield assert(n == 0)
            end for
        }

        "register pipe fd and receive EVFILT_READ on write" in {
            assumeKqueue()
            val b = Ffi.load[KqueueBindings]

            // Create a pipe
            val pipeBuf = Buffer.alloc[Int](2)
            assert(b.pipe(pipeBuf) == 0)
            val readFd  = pipeBuf.get(0)
            val writeFd = pipeBuf.get(1)

            val kq = b.kqueue()
            assert(kq >= 0)

            // Register the read end for EVFILT_READ via a changelist kevent
            val changeBuf = Buffer.alloc[Byte](KEVENT_SIZE)
            writeKevent(changeBuf, 0, readFd.toLong, EVFILT_READ, (EV_ADD | EV_ENABLE).toShort, 0, 0L, 0L)

            val emptyResult = Buffer.alloc[Byte](KEVENT_SIZE)
            val ts0         = zeroTimeout()

            // Write a byte to make the read end readable
            val wb = Buffer.alloc[Byte](1)
            wb.set(0, 42.toByte)

            // Poll for events
            val resultBuf  = Buffer.alloc[Byte](KEVENT_SIZE)
            val dummyEmpty = Buffer.alloc[Byte](1)
            val ts1        = msTimeout(1000)
            for
                // Submit the changelist; no events expected yet
                rc      <- b.kevent(kq, changeBuf, 1, emptyResult, 0, ts0).safe.get
                written <- b.write(writeFd, wb, 1).safe.get
                n       <- b.kevent(kq, dummyEmpty, 0, resultBuf, 1, ts1).safe.get
                _       <- b.close(kq).safe.get
                _       <- b.close(readFd).safe.get
                _       <- b.close(writeFd).safe.get
            yield
                assert(rc >= 0)
                assert(written == 1L)
                assert(n == 1)
                // The returned event should have ident == readFd and filter == EVFILT_READ
                assert(readIdent(resultBuf, 0) == readFd.toLong)
                assert(readFilter(resultBuf, 0) == EVFILT_READ)
                // data field should indicate 1 byte available
                assert(readData(resultBuf, 0) == 1L)
            end for
        }

        "EV_DELETE removes an fd and kevent no longer reports it" in {
            assumeKqueue()
            val b = Ffi.load[KqueueBindings]

            val pipeBuf = Buffer.alloc[Int](2)
            assert(b.pipe(pipeBuf) == 0)
            val readFd  = pipeBuf.get(0)
            val writeFd = pipeBuf.get(1)

            val kq = b.kqueue()
            assert(kq >= 0)

            // Add then delete
            val changeBuf = Buffer.alloc[Byte](KEVENT_SIZE)
            writeKevent(changeBuf, 0, readFd.toLong, EVFILT_READ, (EV_ADD | EV_ENABLE).toShort, 0, 0L, 0L)

            val delBuf = Buffer.alloc[Byte](KEVENT_SIZE)
            writeKevent(delBuf, 0, readFd.toLong, EVFILT_READ, EV_DELETE, 0, 0L, 0L)

            // Write to the pipe: the read end is readable but NOT monitored
            val wb = Buffer.alloc[Byte](1)
            wb.set(0, 99.toByte)

            // kevent should time out with zero events
            val resultBuf = Buffer.alloc[Byte](KEVENT_SIZE)
            val ts        = msTimeout(50)
            for
                addRc   <- b.kevent(kq, changeBuf, 1, Buffer.alloc[Byte](KEVENT_SIZE), 0, zeroTimeout()).safe.get
                delRc   <- b.kevent(kq, delBuf, 1, Buffer.alloc[Byte](KEVENT_SIZE), 0, zeroTimeout()).safe.get
                written <- b.write(writeFd, wb, 1).safe.get
                n       <- b.kevent(kq, Buffer.alloc[Byte](1), 0, resultBuf, 1, ts).safe.get
                _       <- b.close(kq).safe.get
                _       <- b.close(readFd).safe.get
                _       <- b.close(writeFd).safe.get
            yield
                assert(addRc >= 0)
                assert(delRc >= 0)
                assert(written == 1L)
                assert(n == 0)
            end for
        }

        "multiple events from multiple pipes" in {
            assumeKqueue()
            val b = Ffi.load[KqueueBindings]

            val pipe1 = Buffer.alloc[Int](2)
            val pipe2 = Buffer.alloc[Int](2)
            assert(b.pipe(pipe1) == 0)
            assert(b.pipe(pipe2) == 0)
            val r1 = pipe1.get(0)
            val w1 = pipe1.get(1)
            val r2 = pipe2.get(0)
            val w2 = pipe2.get(1)

            val kq = b.kqueue()
            assert(kq >= 0)

            // Register both read ends in a single changelist with 2 kevents
            val changeBuf = Buffer.alloc[Byte](KEVENT_SIZE * 2)
            writeKevent(changeBuf, 0, r1.toLong, EVFILT_READ, (EV_ADD | EV_ENABLE).toShort, 0, 0L, 0L)
            writeKevent(changeBuf, KEVENT_SIZE, r2.toLong, EVFILT_READ, (EV_ADD | EV_ENABLE).toShort, 0, 0L, 0L)

            val wb = Buffer.alloc[Byte](1)

            // Poll for 2 events
            val resultBuf = Buffer.alloc[Byte](KEVENT_SIZE * 2)
            val ts        = msTimeout(1000)
            for
                regRc <- b.kevent(kq, changeBuf, 2, Buffer.alloc[Byte](KEVENT_SIZE), 0, zeroTimeout()).safe.get
                _ <-
                    wb.set(0, 1.toByte)
                    b.write(w1, wb, 1).safe.get.map(written => assert(written == 1L))
                _ <-
                    wb.set(0, 2.toByte)
                    b.write(w2, wb, 1).safe.get.map(written => assert(written == 1L))
                n <- b.kevent(kq, Buffer.alloc[Byte](1), 0, resultBuf, 2, ts).safe.get
                _ <- b.close(kq).safe.get
                _ <- b.close(r1).safe.get
                _ <- b.close(w1).safe.get
                _ <- b.close(r2).safe.get
                _ <- b.close(w2).safe.get
            yield
                assert(regRc >= 0)
                assert(n == 2)
                // Both events should have the pipe read fds as ident
                val idents = Set(
                    readIdent(resultBuf, 0).toInt,
                    readIdent(resultBuf, KEVENT_SIZE).toInt
                )
                assert(idents.contains(r1))
                assert(idents.contains(r2))
            end for
        }

        "data field reports bytes available" in {
            assumeKqueue()
            val b = Ffi.load[KqueueBindings]

            val pipeBuf = Buffer.alloc[Int](2)
            assert(b.pipe(pipeBuf) == 0)
            val readFd  = pipeBuf.get(0)
            val writeFd = pipeBuf.get(1)

            val kq = b.kqueue()
            assert(kq >= 0)

            val changeBuf = Buffer.alloc[Byte](KEVENT_SIZE)
            writeKevent(changeBuf, 0, readFd.toLong, EVFILT_READ, (EV_ADD | EV_ENABLE).toShort, 0, 0L, 0L)

            // Write 5 bytes
            val wb = Buffer.alloc[Byte](5)
            var i  = 0
            while i < 5 do
                wb.set(i, (i + 1).toByte)
                i += 1

            // The data field should report at least 5 bytes available
            val resultBuf = Buffer.alloc[Byte](KEVENT_SIZE)
            val ts        = msTimeout(1000)
            for
                regRc   <- b.kevent(kq, changeBuf, 1, Buffer.alloc[Byte](KEVENT_SIZE), 0, zeroTimeout()).safe.get
                written <- b.write(writeFd, wb, 5).safe.get
                n       <- b.kevent(kq, Buffer.alloc[Byte](1), 0, resultBuf, 1, ts).safe.get
                _       <- b.close(kq).safe.get
                _       <- b.close(readFd).safe.get
                _       <- b.close(writeFd).safe.get
            yield
                assert(regRc >= 0)
                assert(written == 5L)
                assert(n == 1)
                assert(readData(resultBuf, 0) == 5L)
            end for
        }
    }
end KqueueTest
