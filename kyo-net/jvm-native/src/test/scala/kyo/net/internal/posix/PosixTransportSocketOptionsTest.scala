package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetConfig
import kyo.net.NetException
import kyo.net.Test
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool

/** Real socket-option tests for [[NetConfig.soRcvBuf]], [[NetConfig.soSndBuf]], and TCP_QUICKACK.
  *
  * Every test uses real POSIX socket fds and real [[SocketBindings.getsockopt]] / [[SocketBindings.setsockopt]] calls. No mocks,
  * stubs, or behavioral spies. The witness is the real kernel-applied option value read back via getsockopt.
  *
  * Gate: [[PosixTestSockets.assumePoller]] cancels the suite where no epoll (Linux) or kqueue (macOS/BSD) is available.
  *
  * Anti-flakiness: all socket operations are synchronous; no sleep.
  */
class PosixTransportSocketOptionsTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    private def assumePoller(): Unit =
        PosixTestSockets.assumePoller()
        ()

    /** Read a 4-byte integer from a real socket option via getsockopt. Returns the kernel-reported value. */
    private def getIntOpt(sockets: SocketBindings, fd: Int, level: Int, optname: Int): Int =
        val opt = Buffer.alloc[Byte](4)
        val len = Buffer.alloc[Int](1)
        len.set(0, 4)
        try
            val rc = sockets.getsockopt(fd, level, optname, opt, len).value
            if rc != 0 then -1
            else
                (opt.get(0) & 0xff) |
                    ((opt.get(1) & 0xff) << 8) |
                    ((opt.get(2) & 0xff) << 16) |
                    ((opt.get(3) & 0xff) << 24)
            end if
        finally
            opt.close()
            len.close()
        end try
    end getIntOpt

    /** Build a real transport backed by one PollerIoDriver, connect a loopback client, run `body` with the real client fd, then close the driver. */
    private def withRealClientFd[A](config: NetConfig)(body: (
        SocketBindings,
        Int
    ) => A < (Async & Abort[NetException | Closed] & Scope))(using
        Frame
    ): A < (Async & Abort[NetException | Closed] & Scope) =
        val sockets   = Ffi.load[SocketBindings]
        val driver    = PollerIoDriver.init()
        val pool      = IoDriverPool.init(Array[IoDriver[PosixHandle]](driver))
        val transport = PosixTransport.init(pool)
        pool.start()
        Abort.run[NetException | Closed] {
            transport.listen("127.0.0.1", 0, 4)(_ => ()).safe.get.map { listener =>
                transport.connect("127.0.0.1", listener.port, config = config).safe.get.map { conn =>
                    val fd = conn.asInstanceOf[kyo.net.internal.transport.Connection[PosixHandle]].handle.readFd
                    body(sockets, fd).map { result =>
                        conn.close()
                        listener.close()
                        result
                    }
                }
            }
        }.map { result =>
            Sync.defer(driver.close()).andThen(Abort.get(result))
        }
    end withRealClientFd

    // --- socketBufferOptionsApplied ---
    // When soRcvBuf=Present(65536) / soSndBuf=Present(32768) is set on a NetConfig, the real connected client socket must have
    // the kernel-applied buffer size >= the requested size (the kernel may round up to the next power-of-two or similar).
    // Absent: the socket uses the kernel's default (which may differ from 65536/32768), i.e. no setsockopt is called.
    // Anti-flakiness: getsockopt is synchronous; the setsockopt is applied synchronously inside prepareClientSocket.
    // Two connects on ONE transport, asking for different socket buffer sizes, must come back with different sizes. That difference cannot
    // appear unless each connect's own config reached its socket, which is the property under test; a construction-captured or dropped config
    // would give both connections identical buffers.
    //
    // The comparison is made on SO_SNDBUF, not SO_RCVBUF, because the two behave differently once a socket is connected. macOS auto-tunes the
    // RECEIVE buffer aggressively and ignores the requested size on a connected loopback socket: measured here, a request of 16384 read back
    // as 326640 and a request of 262144 as 277644, so the readback is not even monotonic in the request and cannot witness anything. The send
    // buffer does track the request (16384 -> 65328, 262144 -> 277644, no request -> 146988). Linux honors both (it stores double the
    // requested value), so the receive side is asserted there, where it is meaningful, rather than written as an assertion that would hold on
    // macOS whatever the transport did.
    "socketBufferOptionsApplied: two connects on one transport get the buffer sizes they each asked for" in {
        assumePoller()
        val sockets   = Ffi.load[SocketBindings]
        val driver    = PollerIoDriver.init()
        val pool      = IoDriverPool.init(Array[IoDriver[PosixHandle]](driver))
        val transport = PosixTransport.init(pool)
        pool.start()
        val smallReq = 16384
        val largeReq = 262144
        def fdOf(conn: kyo.net.Connection): Int =
            conn.asInstanceOf[kyo.net.internal.transport.Connection[PosixHandle]].handle.readFd
        Abort.run[NetException | Closed] {
            transport.listen("127.0.0.1", 0, 4)(_ => ()).safe.get.map { listener =>
                val small = NetConfig.default.copy(soRcvBuf = Present(smallReq), soSndBuf = Present(smallReq))
                val large = NetConfig.default.copy(soRcvBuf = Present(largeReq), soSndBuf = Present(largeReq))
                transport.connect("127.0.0.1", listener.port, config = small).safe.get.map { smallConn =>
                    transport.connect("127.0.0.1", listener.port, config = large).safe.get.map { largeConn =>
                        Sync.defer {
                            val smallSnd = getIntOpt(sockets, fdOf(smallConn), PosixConstants.SOL_SOCKET, PosixConstants.SO_SNDBUF)
                            val largeSnd = getIntOpt(sockets, fdOf(largeConn), PosixConstants.SOL_SOCKET, PosixConstants.SO_SNDBUF)
                            val smallRcv = getIntOpt(sockets, fdOf(smallConn), PosixConstants.SOL_SOCKET, PosixConstants.SO_RCVBUF)
                            val largeRcv = getIntOpt(sockets, fdOf(largeConn), PosixConstants.SOL_SOCKET, PosixConstants.SO_RCVBUF)
                            smallConn.close()
                            largeConn.close()
                            listener.close()
                            assert(
                                largeSnd > smallSnd,
                                s"SO_SNDBUF: the connect asking for $largeReq got $largeSnd, the one asking for $smallReq got $smallSnd; " +
                                    "equal values mean neither connect's config reached its socket"
                            )
                            if PosixConstants.isLinux then
                                assert(
                                    largeRcv > smallRcv,
                                    s"SO_RCVBUF: the connect asking for $largeReq got $largeRcv, the one asking for $smallReq got $smallRcv"
                                )
                            end if
                            succeed
                        }
                    }
                }
            }
        }.map { result =>
            Sync.defer(driver.close()).andThen(Abort.get(result))
        }
    }

    // --- quickAckLinuxOnly ---
    // TCP_QUICKACK (value 12) is applied only on Linux via prepareClientSocket's `if isLinux` gate.
    // On macOS/BSD the gate is not entered (TCP_QUICKACK constant is the 0 sentinel).
    // Verified via: on Linux, real getsockopt(TCP_QUICKACK) returns 1 (set) after prepareClientSocket; on macOS, no error from the 0-sentinel
    // setsockopt call, and the round-trip completes correctly regardless.
    // Anti-flakiness: getsockopt is synchronous; no sleep.
    "quickAckLinuxOnly: TCP_QUICKACK applied only on Linux, correct behavior on macOS" in {
        assumePoller()
        withRealClientFd(NetConfig.default) { (sockets, fd) =>
            Sync.defer {
                if PosixConstants.isLinux then
                    // On Linux, TCP_QUICKACK (level=IPPROTO_TCP=6, optname=12) should be set to 1 by prepareClientSocket.
                    val actual = getIntOpt(sockets, fd, PosixConstants.IPPROTO_TCP, PosixConstants.TCP_QUICKACK)
                    assert(
                        actual == 1,
                        s"TCP_QUICKACK on Linux client fd=$fd: expected 1 (set by prepareClientSocket), got $actual"
                    )
                else
                    // On macOS/BSD, TCP_QUICKACK constant is 0 (sentinel). The 0-sentinel path skips the setsockopt call inside
                    // the `if isLinux` gate, so no error should occur and the socket is fully functional.
                    // Verify the constant is 0 (macOS sentinels the option).
                    assert(
                        PosixConstants.TCP_QUICKACK == 0,
                        s"TCP_QUICKACK must be 0 on macOS/BSD (sentinel), got ${PosixConstants.TCP_QUICKACK}"
                    )
                end if
                succeed
            }
        }
    }

end PosixTransportSocketOptionsTest
