package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.ffi.StructLayout
import kyo.internal.UnsafeLayout
import kyo.net.Test

/** Integration test for [[KqueueBindings]] on macOS/BSD, productionized from kyo-ffi-it's `KqueueTest` but driving the codegen `KEvent` /
  * `Timespec` structs (via [[StructLayout]]) instead of hand-laid `Buffer[Byte]`.
  *
  * Registers a connected loopback socket for `EVFILT_READ`, writes a byte to its peer, and confirms `kevent` reports the fd readable with the
  * `EVFILT_READ` filter and the data byte count. Skips on non-macOS/BSD hosts (`sys/event.h` absent / stubbed).
  *
  * `kevent`, `KqueueBindings.close`, and the socket `connect`/`accept`/`send`/`close` calls are `@Ffi.blocking`, so they are generated as
  * fiber-suspending `… < Async`; the `loopbackPair` helper and the test body are threaded through the suite's async `run`.
  */
class KqueueBindingsTest extends Test:

    import AllowUnsafe.embrace.danger

    private given UnsafeLayout[KEvent]   = StructLayout.derived[KEvent]
    private given UnsafeLayout[Timespec] = StructLayout.derived[Timespec]

    private def assumeKqueue(): Unit =
        if !PosixConstants.isMacOrBsd then cancel("kqueue is macOS/BSD-only")

    private def sock = Ffi.load[SocketBindings]
    private def kq   = Ffi.load[KqueueBindings]

    /** Build a connected loopback socket pair and return (clientFd, acceptedFd). `connect`/`accept`/`close` are `@Ffi.blocking`, so this is a
      * fiber-suspending `… < Async`. errno is consulted only on failure (POSIX leaves it untouched on success).
      */
    private def loopbackPair()(using Frame, kyo.test.AssertScope): (Int, Int) < Async =
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(a.close())) {
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sock.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
            val connected =
                Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0)))
            connected.andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sock.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map { accepted =>
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

    "KqueueBindings" - {
        "kqueue + kevent EVFILT_READ reports a readable socket" in {
            assumeKqueue()
            val kqfd = kq.kqueue()
            assert(kqfd.value >= 0, s"kqueue failed errno=${kqfd.errorCode}")
            for
                pair <- loopbackPair()
                (client, accepted) = pair
                change             = Buffer.alloc[KEvent](1)
                _ = change.set(
                    0,
                    KEvent(
                        ident = accepted.toLong,
                        filter = PosixConstants.EVFILT_READ,
                        flags = (PosixConstants.EV_ADD | PosixConstants.EV_ENABLE).toShort,
                        fflags = 0,
                        data = 0L,
                        udata = 0L
                    )
                )
                emptyEvents = Buffer.alloc[KEvent](1)
                // Register the accepted fd for EVFILT_READ.
                reg <- kq.kevent(kqfd.value, change, 1, emptyEvents, 0, Timespec(0L, 0L)).safe.get
                _ = assert(reg.value >= 0, s"kevent register failed errno=${reg.errorCode}")
                _ = change.close()
                _ = emptyEvents.close()
                // Client writes 5 bytes; the accepted fd becomes readable.
                wb = Buffer.fromArray[Byte](Array[Byte](1, 2, 3, 4, 5))
                _ <- Sync.ensure(Sync.defer(wb.close())) {
                    sock.send(client, wb, 5L, PosixConstants.MSG_NOSIGNAL).safe.get.map(r => assert(r.value == 5L))
                }
                events    = Buffer.alloc[KEvent](1)
                emptyPoll = Buffer.alloc[KEvent](1)
                pollResult <- Sync.ensure(Sync.defer { events.close(); emptyPoll.close() }) {
                    kq.kevent(kqfd.value, emptyPoll, 0, events, 1, Timespec(1L, 0L)).safe.get.map { n =>
                        (n, events.get(0))
                    }
                }
                _ <- sock.close(client).safe.get
                _ <- sock.close(accepted).safe.get
                _ <- kq.close(kqfd.value).safe.get
            yield
                val (n, ev) = pollResult
                assert(n.value == 1, s"kevent poll returned ${n.value} errno=${n.errorCode}")
                assert(ev.ident == accepted.toLong)
                assert(ev.filter == PosixConstants.EVFILT_READ)
                assert(ev.data == 5L)
            end for
        }
    }
end KqueueBindingsTest
