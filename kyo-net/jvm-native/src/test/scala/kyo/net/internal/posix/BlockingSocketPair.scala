package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** A connected TCP loopback pair whose descriptors stay in blocking mode, for a peer that waits in `recv` for the next real event (data, FIN or
  * RST) instead of polling, and for a blocking `read(2)` that must stay in flight until the test releases it.
  */
object BlockingSocketPair:

    /** Returns (clientFd, acceptedFd), both blocking. */
    def open()(using Frame, AllowUnsafe): (Int, Int) < Async =
        val sockets = Ffi.load[SocketBindings]
        val server  = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(???)
        Sync.ensure(Sync.defer(a.close())) {
            assert(sockets.bind(server, a, l).value == 0)
            assert(sockets.listen(server, 4).value == 0)
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val port =
                try
                    assert(sockets.getsockname(server, out, ol).value == 0)
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            val client   = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
            val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(???)
            Sync.ensure(Sync.defer(ca.close()))(sockets.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0))).andThen {
                val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
                val noLen  = Buffer.alloc[Int](1)
                noLen.set(0, SockAddr.inet4Size)
                Sync.ensure(Sync.defer { noAddr.close(); noLen.close() }) {
                    sockets.accept(server, noAddr, noLen).safe.get.map(_.value)
                }.map(accepted => sockets.close(server).safe.get.map(_ => (client, accepted)))
            }
        }
    end open

end BlockingSocketPair
