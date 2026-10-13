package kyo.net

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.posix.PollerIoDriver
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.PosixShimBindings
import kyo.net.internal.posix.SockAddr
import kyo.net.internal.posix.SocketBindings
import kyo.net.internal.posix.TestTransports

/** The JVM and Native connections no listener produces, for [[Test.eachEndpoint]]: the split-fd connection `PosixTransport.stdio` builds, and
  * the same connection reading through the [[kyo.net.internal.posix.BlockingReaderDriver]] fallback.
  *
  * Each reads one loopback socket and writes another, so its far end is a [[PosixRawPeer]] over the two opposite ends and a leaf can send,
  * half-close and reset as with a TCP peer. The blocking-reader cell's read socket is left blocking, the descriptor kind that fallback
  * serves, so its `read(2)` parks until bytes or the peer's FIN arrive. The connection never closes a split handle's descriptors, so the
  * cell closes them once the leaf's driver has stopped.
  */
object TestEndpoints:

    import AllowUnsafe.embrace.danger

    val all: Seq[Test.EndpointCell] =
        Seq(cell("posix split-fd", blockingReader = false), cell("blocking-reader", blockingReader = true))

    private def cell(name: String, blockingReader: Boolean): Test.EndpointCell =
        val unavailable =
            if PosixConstants.isLinux || PosixConstants.isMacOrBsd then Absent
            else Present(s"the $name cell needs the epoll or kqueue poller")
        Test.EndpointCell(name, unavailable, frame => open(blockingReader)(using frame))
    end cell

    private def open(blockingReader: Boolean)(using Frame): (Connection, RawPeer) < (Async & Scope) =
        val sockets = Ffi.load[SocketBindings]
        for
            (connRead, peerWrite) <- pair(sockets, nonBlocking = !blockingReader)
            (connWrite, peerRead) <- pair(sockets, nonBlocking = true)
            driver = PollerIoDriver.init()
            loop   = driver.start()
            _ <- Scope.ensure(Sync.defer(driver.close()).andThen(loop.safe.get).andThen(Sync.defer {
                discard(sockets.close(connRead))
                discard(sockets.close(connWrite))
            }))
            transport = TestTransports.forTesting(driver, sockets, backendIsEpoll = PosixConstants.isLinux)
            conn      = TestTransports.splitConnection(transport, driver, connRead, connWrite, 16, blockingReader)
            _    <- Scope.ensure(Sync.defer(conn.close()))
            peer <- PosixRawPeer.init(sockets, peerRead, peerWrite)
        yield (conn, peer)
        end for
    end open

    /** A connected loopback pair: the first descriptor for the connection, set non-blocking when `nonBlocking`, the second for the peer,
      * always blocking.
      */
    private def pair(sockets: SocketBindings, nonBlocking: Boolean)(using Frame): (Int, Int) < Async =
        val server = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(throw new IllegalStateException("loopback"))
        try
            require(sockets.bind(server, a, l).value == 0, "bind")
            require(sockets.listen(server, 1).value == 0, "listen")
        finally a.close()
        end try
        val out = Buffer.alloc[Byte](SockAddr.inet4Size)
        val ol  = Buffer.alloc[Int](1)
        ol.set(0, SockAddr.inet4Size)
        val port =
            try
                require(sockets.getsockname(server, out, ol).value == 0, "getsockname")
                ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
            finally
                out.close()
                ol.close()
        val client   = sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) =
            SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(throw new IllegalStateException("loopback"))
        Sync.ensure(Sync.defer(ca.close()))(sockets.connect(client, ca, cl).safe.get).map { r =>
            require(r.value == 0, s"loopback connect failed: errno ${r.errorCode}")
            val noAddr = Buffer.alloc[Byte](SockAddr.inet4Size)
            val noLen  = Buffer.alloc[Int](1)
            noLen.set(0, SockAddr.inet4Size)
            Sync.ensure(Sync.defer { noAddr.close(); noLen.close() })(sockets.accept(server, noAddr, noLen).safe.get).map { accepted =>
                discard(sockets.close(server))
                if PosixConstants.isMacOrBsd then
                    Seq(client, accepted.value).foreach { fd =>
                        val one = Buffer.alloc[Byte](4)
                        one.setIntAt(0, 1)
                        try discard(sockets.setsockopt(fd, PosixConstants.SOL_SOCKET, PosixConstants.SO_NOSIGPIPE, one, 4))
                        finally one.close()
                    }
                end if
                if nonBlocking then require(Ffi.load[PosixShimBindings].kyo_posix_set_nonblocking(client) == 0, "set_nonblocking")
                (client, accepted.value)
            }
        }
    end pair

end TestEndpoints
