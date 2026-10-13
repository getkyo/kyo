package kyo.net

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.internal.posix.PosixConstants
import kyo.net.internal.posix.SockAddr
import kyo.net.internal.posix.SocketBindings

/** [[RawPeer]] on Native: a [[PosixRawPeer]] over the same socket bindings the posix transport uses. */
private[net] object RawPeerPlatform:

    import AllowUnsafe.embrace.danger

    def connect(port: Int, rcvBuf: Maybe[Int])(using Frame): RawPeer < (Async & Scope) =
        val sockets = Ffi.load[SocketBindings]
        val fd      = configured(sockets, sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value, rcvBuf)
        val (a, l)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(throw new IllegalStateException("loopback"))
        PosixRawPeer.init(sockets, fd, fd).map { peer =>
            Sync.ensure(Sync.defer(a.close()))(sockets.connect(fd, a, l).safe.get).map { r =>
                require(r.value == 0, s"raw peer connect to $port failed: errno ${r.errorCode}")
                peer
            }
        }
    end connect

    def listen(rcvBuf: Maybe[Int])(using Frame): RawPeer.Listener < (Async & Scope) =
        val sockets = Ffi.load[SocketBindings]
        val fd      = configured(sockets, sockets.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value, rcvBuf)
        val (a, l)  = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", 0).getOrElse(throw new IllegalStateException("loopback"))
        Scope.ensure(Sync.defer(discard(sockets.close(fd)))).andThen {
            try
                require(sockets.bind(fd, a, l).value == 0, "bind")
                require(sockets.listen(fd, 4).value == 0, "listen")
            finally a.close()
            end try
            val out = Buffer.alloc[Byte](SockAddr.inet4Size)
            val ol  = Buffer.alloc[Int](1)
            ol.set(0, SockAddr.inet4Size)
            val boundPort =
                try
                    require(sockets.getsockname(fd, out, ol).value == 0, "getsockname")
                    ((out.get(2) & 0xff) << 8) | (out.get(3) & 0xff)
                finally
                    out.close()
                    ol.close()
            new RawPeer.Listener:
                val port                                             = boundPort
                def accept()(using Frame): RawPeer < (Async & Scope) =
                    val addr = Buffer.alloc[Byte](SockAddr.inet4Size)
                    val len  = Buffer.alloc[Int](1)
                    len.set(0, SockAddr.inet4Size)
                    Sync.ensure(Sync.defer { addr.close(); len.close() })(sockets.accept(fd, addr, len).safe.get).map { accepted =>
                        val peerFd = configured(sockets, accepted.value, Absent)
                        PosixRawPeer.init(sockets, peerFd, peerFd)
                    }
                end accept
            end new
        }
    end listen

    private def configured(sockets: SocketBindings, fd: Int, rcvBuf: Maybe[Int]): Int =
        if PosixConstants.isMacOrBsd then setInt(sockets, fd, PosixConstants.SO_NOSIGPIPE, 1)
        rcvBuf.foreach(n => setInt(sockets, fd, PosixConstants.SO_RCVBUF, n))
        fd
    end configured

    private def setInt(sockets: SocketBindings, fd: Int, option: Int, value: Int): Unit =
        val v = Buffer.alloc[Byte](4)
        v.setIntAt(0, value)
        try discard(sockets.setsockopt(fd, PosixConstants.SOL_SOCKET, option, v, 4))
        finally v.close()
    end setInt

end RawPeerPlatform
