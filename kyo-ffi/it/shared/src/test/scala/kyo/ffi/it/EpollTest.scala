package kyo.ffi.it

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** Integration test for Linux epoll via kyo-ffi.
  *
  * Exercises the full lifecycle: `epoll_create1` -> `epoll_ctl` -> `epoll_wait` -> `close`. Uses a pipe pair to trigger `EPOLLIN` events.
  *
  * `epoll_wait`, `write`, `read`, and `close` are `@Ffi.blocking`, so the generator returns a `Fiber.Unsafe`; the call sites await it with
  * `.safe.get` and the test bodies are threaded through the suite's async `run`. The non-blocking calls (`epoll_create1`, `epoll_ctl`,
  * `pipe`) return plain values and only need `AllowUnsafe` in scope.
  *
  * All tests skip with `cancel` on non-Linux hosts where epoll is unavailable.
  */
class EpollTest extends ItTestBase:

    import AllowUnsafe.embrace.danger

    private val isLinux: Boolean =
        java.lang.System.getProperty("os.name", "").toLowerCase.contains("linux")

    private def assumeEpoll(): Unit =
        if !isLinux then cancel("epoll is Linux-only")

    // struct epoll_event layout is architecture-dependent. glibc applies
    // __attribute__((packed)) only on x86_64, where the 8-byte data union immediately follows the
    // 4-byte events field (data at offset 4, total size 12). On every other architecture
    // (aarch64, ...) the struct uses natural alignment: 4 bytes of padding follow events, so data
    // sits at offset 8 and the total size is 16.
    //   uint32_t     events;  // offset 0, 4 bytes
    //   epoll_data_t data;    // offset EPOLL_DATA_OFFSET, 8 bytes (union, we use the u64 member)
    private val isX86_64: Boolean =
        val arch = java.lang.System.getProperty("os.arch", "").toLowerCase
        arch == "amd64" || arch == "x86_64" || arch == "x64"
    private val EPOLL_DATA_OFFSET = if isX86_64 then 4 else 8
    private val EPOLL_EVENT_SIZE  = if isX86_64 then 12 else 16

    private val EPOLL_CTL_ADD = 1
    private val EPOLL_CTL_DEL = 2
    private val EPOLLIN       = 0x001
    private val EPOLLOUT      = 0x004

    /** Write the fields of a `struct epoll_event` into `buf` at the given byte offset. Little-endian layout. */
    private def writeEpollEvent(buf: Buffer[Byte], offset: Int, events: Int, data: Long): Unit =
        // events (uint32_t LE) at offset
        buf.set(offset + 0, (events & 0xff).toByte)
        buf.set(offset + 1, ((events >> 8) & 0xff).toByte)
        buf.set(offset + 2, ((events >> 16) & 0xff).toByte)
        buf.set(offset + 3, ((events >> 24) & 0xff).toByte)
        // data (uint64_t LE) at offset + EPOLL_DATA_OFFSET
        var i = 0
        while i < 8 do
            buf.set(offset + EPOLL_DATA_OFFSET + i, ((data >> (i * 8)) & 0xff).toByte)
            i += 1
    end writeEpollEvent

    /** Read the `events` field (uint32_t) from a `struct epoll_event` at the given byte offset. */
    private def readEvents(buf: Buffer[Byte], offset: Int): Int =
        (buf.get(offset) & 0xff) |
            ((buf.get(offset + 1) & 0xff) << 8) |
            ((buf.get(offset + 2) & 0xff) << 16) |
            ((buf.get(offset + 3) & 0xff) << 24)

    /** Read the `data.u64` field (uint64_t) from a `struct epoll_event` at the given byte offset. */
    private def readData(buf: Buffer[Byte], offset: Int): Long =
        var v = 0L
        var i = 0
        while i < 8 do
            v |= (buf.get(offset + EPOLL_DATA_OFFSET + i).toLong & 0xff) << (i * 8)
            i += 1
        v
    end readData

    "epoll" - {
        "create + close lifecycle" in {
            assumeEpoll()
            val b    = Ffi.load[EpollBindings]
            val epfd = b.epoll_create1(0)
            assert(epfd >= 0)
            b.close(epfd).safe.get.map(rc => assert(rc == 0))
        }

        "epoll_wait times out with no registered fds" in {
            assumeEpoll()
            val b    = Ffi.load[EpollBindings]
            val epfd = b.epoll_create1(0)
            assert(epfd >= 0)
            val resultBuf = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            for
                n <- b.epoll_wait(epfd, resultBuf, 1, 10).safe.get // 10 ms timeout
                _ <- b.close(epfd).safe.get
            yield assert(n == 0)
            end for
        }

        "register pipe fd and receive EPOLLIN on write" in {
            assumeEpoll()
            val b = Ffi.load[EpollBindings]

            // Create a pipe: pipefds(0)=read end, pipefds(1)=write end
            val pipeBuf = Buffer.alloc[Int](2)
            assert(b.pipe(pipeBuf) == 0)
            val readFd  = pipeBuf.get(0)
            val writeFd = pipeBuf.get(1)

            val epfd = b.epoll_create1(0)
            assert(epfd >= 0)

            // Register the read end for EPOLLIN
            val eventBuf = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            writeEpollEvent(eventBuf, 0, EPOLLIN, readFd.toLong)
            assert(b.epoll_ctl(epfd, EPOLL_CTL_ADD, readFd, eventBuf) == 0)

            // Write a byte to the pipe to make the read end readable
            val writeBuf = Buffer.alloc[Byte](1)
            writeBuf.set(0, 42.toByte)

            val resultBuf = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            for
                written <- b.write(writeFd, writeBuf, 1).safe.get
                // Wait for events, should return 1 event immediately
                n <- b.epoll_wait(epfd, resultBuf, 1, 1000).safe.get // 1 s timeout
                _ <- b.close(epfd).safe.get
                _ <- b.close(readFd).safe.get
                _ <- b.close(writeFd).safe.get
            yield
                assert(written == 1L)
                assert(n == 1)
                // Verify the returned event has EPOLLIN set
                val events = readEvents(resultBuf, 0)
                assert((events & EPOLLIN) != 0)
                // Verify the returned data field matches the fd we registered
                assert(readData(resultBuf, 0) == readFd.toLong)
            end for
        }

        "EPOLL_CTL_DEL removes an fd and epoll_wait no longer reports it" in {
            assumeEpoll()
            val b = Ffi.load[EpollBindings]

            val pipeBuf = Buffer.alloc[Int](2)
            assert(b.pipe(pipeBuf) == 0)
            val readFd  = pipeBuf.get(0)
            val writeFd = pipeBuf.get(1)

            val epfd = b.epoll_create1(0)
            assert(epfd >= 0)

            // Add then remove the read fd
            val eventBuf = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            writeEpollEvent(eventBuf, 0, EPOLLIN, readFd.toLong)
            assert(b.epoll_ctl(epfd, EPOLL_CTL_ADD, readFd, eventBuf) == 0)
            assert(b.epoll_ctl(epfd, EPOLL_CTL_DEL, readFd, eventBuf) == 0)

            // Write to the pipe: the read end is readable but NOT monitored
            val writeBuf = Buffer.alloc[Byte](1)
            writeBuf.set(0, 99.toByte)

            val resultBuf = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            for
                written <- b.write(writeFd, writeBuf, 1).safe.get
                // epoll_wait should time out with zero events
                n <- b.epoll_wait(epfd, resultBuf, 1, 50).safe.get // 50 ms timeout
                _ <- b.close(epfd).safe.get
                _ <- b.close(readFd).safe.get
                _ <- b.close(writeFd).safe.get
            yield
                assert(written == 1L)
                assert(n == 0)
            end for
        }

        "multiple events from multiple pipes" in {
            assumeEpoll()
            val b = Ffi.load[EpollBindings]

            val pipe1 = Buffer.alloc[Int](2)
            val pipe2 = Buffer.alloc[Int](2)
            assert(b.pipe(pipe1) == 0)
            assert(b.pipe(pipe2) == 0)
            val r1 = pipe1.get(0)
            val w1 = pipe1.get(1)
            val r2 = pipe2.get(0)
            val w2 = pipe2.get(1)

            val epfd = b.epoll_create1(0)
            assert(epfd >= 0)

            // Register both read ends
            val ev1 = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            writeEpollEvent(ev1, 0, EPOLLIN, r1.toLong)
            assert(b.epoll_ctl(epfd, EPOLL_CTL_ADD, r1, ev1) == 0)

            val ev2 = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            writeEpollEvent(ev2, 0, EPOLLIN, r2.toLong)
            assert(b.epoll_ctl(epfd, EPOLL_CTL_ADD, r2, ev2) == 0)

            // Write to both pipes
            val wb = Buffer.alloc[Byte](1)

            val resultBuf = Buffer.alloc[Byte](EPOLL_EVENT_SIZE * 2)
            for
                _ <-
                    wb.set(0, 1.toByte)
                    b.write(w1, wb, 1).safe.get.map(written => assert(written == 1L))
                _ <-
                    wb.set(0, 2.toByte)
                    b.write(w2, wb, 1).safe.get.map(written => assert(written == 1L))
                // Wait for 2 events
                n <- b.epoll_wait(epfd, resultBuf, 2, 1000).safe.get
                _ <- b.close(epfd).safe.get
                _ <- b.close(r1).safe.get
                _ <- b.close(w1).safe.get
                _ <- b.close(r2).safe.get
                _ <- b.close(w2).safe.get
            yield
                assert(n == 2)
                // Both events should have EPOLLIN
                val fds = Set(
                    readData(resultBuf, 0).toInt,
                    readData(resultBuf, EPOLL_EVENT_SIZE).toInt
                )
                assert(fds.contains(r1))
                assert(fds.contains(r2))
            end for
        }

        "data round-trips through epoll_event" in {
            assumeEpoll()
            val b = Ffi.load[EpollBindings]

            val pipeBuf = Buffer.alloc[Int](2)
            assert(b.pipe(pipeBuf) == 0)
            val readFd  = pipeBuf.get(0)
            val writeFd = pipeBuf.get(1)

            val epfd = b.epoll_create1(0)
            assert(epfd >= 0)

            // Register with a distinctive data value (not the fd)
            val sentinel = 0xdeadbeef_cafebabeL
            val eventBuf = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            writeEpollEvent(eventBuf, 0, EPOLLIN, sentinel)
            assert(b.epoll_ctl(epfd, EPOLL_CTL_ADD, readFd, eventBuf) == 0)

            // Trigger readable
            val wb = Buffer.alloc[Byte](1)
            wb.set(0, 7.toByte)

            val resultBuf = Buffer.alloc[Byte](EPOLL_EVENT_SIZE)
            for
                written <- b.write(writeFd, wb, 1).safe.get
                n       <- b.epoll_wait(epfd, resultBuf, 1, 1000).safe.get
                _       <- b.close(epfd).safe.get
                _       <- b.close(readFd).safe.get
                _       <- b.close(writeFd).safe.get
            yield
                assert(written == 1L)
                assert(n == 1)
                assert(readData(resultBuf, 0) == sentinel)
            end for
        }
    }
end EpollTest
