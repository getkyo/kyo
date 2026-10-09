package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** The Native socket behind `kyo.internal.ResettingServerImpl`. Lives in this package because the socket bindings are `private[net]`. */
object ResettingPosixServer:

    private def sockets(using AllowUnsafe) = Ffi.load[SocketBindings]

    /** A blocking TCP listener on 127.0.0.1 at an ephemeral port; returns (serverFd, port). Blocking, so the accept waits for a client. */
    def listen()(using AllowUnsafe): (Int, Int) =
        val sock   = sockets
        val server = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (a, l) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(throw new IllegalStateException("127.0.0.1"))
        try
            assert(sock.bind(server, a, l).value == 0)
            assert(sock.listen(server, 1).value == 0)
        finally a.close()
        end try
        val out = Buffer.alloc[Byte](SockAddr.inet4Size)
        val ol  = Buffer.alloc[Int](1)
        ol.set(0, SockAddr.inet4Size)
        try
            assert(sock.getsockname(server, out, ol).value == 0)
            (server, ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff))
        finally
            out.close()
            ol.close()
        end try
    end listen

    /** Accepts one connection on `serverFd`, reads until the request head ends, and writes `response`; returns the accepted fd. */
    def serveHead(serverFd: Int, response: Array[Byte])(using Frame, AllowUnsafe): Int < Async =
        PosixTestSockets.acceptOne(serverFd).map { fd =>
            val buf                                  = Buffer.alloc[Byte](4096)
            def readHead(head: String): Unit < Async =
                if head.contains("\r\n\r\n") then Kyo.unit
                else
                    sockets.recv(fd, buf, 4096L, 0).safe.get.map { n =>
                        if n.value <= 0 then throw new java.io.EOFException("the client closed before its request head ended")
                        else readHead(head + new String(Array.tabulate(n.value.toInt)(buf.get(_)), "ISO-8859-1"))
                    }
            def writeAll(offset: Int): Unit < Async =
                if offset >= response.length then Kyo.unit
                else
                    val rest = Buffer.alloc[Byte](response.length - offset)
                    (offset until response.length).foreach(i => rest.set(i - offset, response(i)))
                    Sync.ensure(Sync.defer(rest.close())) {
                        sockets.send(fd, rest, (response.length - offset).toLong, PosixConstants.MSG_NOSIGNAL).safe.get
                    }.map(n => writeAll(offset + n.value.toInt))
            Sync.ensure(Sync.defer(buf.close()))(readHead("").andThen(writeAll(0))).andThen(fd)
        }

    /** Resets the connection on `fd`: SO_LINGER {1, 0}, then close. */
    def reset(fd: Int)(using AllowUnsafe): Unit = PosixTestSockets.resetPeer(sockets, fd)

    def close(fd: Int)(using AllowUnsafe): Unit = discard(sockets.close(fd))

end ResettingPosixServer
