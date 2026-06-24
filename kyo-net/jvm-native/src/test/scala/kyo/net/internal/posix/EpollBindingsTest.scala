package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** Integration test for [[EpollBindings]] on Linux, productionized from kyo-ffi-it's `EpollTest`, driving the arch-aware hand-laid
  * `struct epoll_event` layout ([[EpollEvent$]]) over `Buffer[Byte]`.
  *
  * On Linux it registers a connected loopback socket for `EPOLLIN`, writes to its peer, and confirms `epoll_wait` reports it with the
  * registered `data` key. Off Linux (`sys/epoll.h` absent / stubbed) the syscall test is skipped; the struct ABI is still covered by
  * `PosixStructsAbiTest` on every platform.
  *
  * `epoll_wait`, `EpollBindings.close`, and the socket `connect`/`accept`/`send`/`close` calls are `@Ffi.blocking`, so they are generated as
  * fiber-suspending `… < Async`; the `loopbackPair` helper and the test body are threaded through the suite's async `run`.
  */
class EpollBindingsTest extends Test:

    import AllowUnsafe.embrace.danger

    private def assumeEpoll(): Unit =
        if !PosixConstants.isLinux then cancel("epoll is Linux-only")

    private def sock = Ffi.load[SocketBindings]
    private def ep   = Ffi.load[EpollBindings]

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

    "EpollBindings" - {
        "epoll_create1 + ctl(ADD) + wait reports readability with the registered key" in {
            assumeEpoll()
            val epfd = ep.epoll_create1(0)
            assert(epfd.value >= 0, s"epoll_create1 failed errno=${epfd.errorCode}")
            for
                pair <- loopbackPair()
                (client, accepted) = pair
                key                = 0xcafeL
                event              = Buffer.alloc[Byte](EpollEvent.size)
                ctlR =
                    try
                        EpollEvent.encode(event, 0, EpollEvent(PosixConstants.EPOLLIN, key))
                        ep.epoll_ctl(epfd.value, PosixConstants.EPOLL_CTL_ADD, accepted, event)
                    finally event.close()
                _  = assert(ctlR.value == 0, s"epoll_ctl failed errno=${ctlR.errorCode}")
                wb = Buffer.fromArray[Byte](Array[Byte](7))
                _ <- Sync.ensure(Sync.defer(wb.close())) {
                    sock.send(client, wb, 1L, PosixConstants.MSG_NOSIGNAL).safe.get.map(r => assert(r.value == 1L))
                }
                events = Buffer.alloc[Byte](4 * EpollEvent.size)
                result <- Sync.ensure(Sync.defer(events.close())) {
                    ep.epoll_wait(epfd.value, events, 4, 1000).safe.get.map { n =>
                        val ev = EpollEvent.decode(events, 0)
                        (n, ev)
                    }
                }
                _ <- sock.close(client).safe.get
                _ <- sock.close(accepted).safe.get
                _ <- ep.close(epfd.value).safe.get
            yield
                val (n, ev) = result
                assert(n.value == 1, s"epoll_wait returned ${n.value} errno=${n.errorCode}")
                assert((ev.events & PosixConstants.EPOLLIN) != 0)
                assert(ev.data == key)
            end for
        }
    }
end EpollBindingsTest
