package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Connection
import kyo.net.NetException
import kyo.net.NetNotUpgradableException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.BoringSslBindings
import kyo.net.internal.TlsTestCert
import kyo.net.internal.transport.Connection as InternalConnection

/** STARTTLS upgrade tests over a real loopback socket pair: the staged ciphertext is fed before the first post-upgrade read so no
  * plaintext byte is dropped, the upgraded connection reuses the SAME fd, and a non-upgradable connection aborts `Closed`.
  *
  * Both sides upgrade through the one `PosixTransport.upgradeRole` machinery (client `isServer=false`, server `isServer=true`), with the
  * handshake driven over the real fds by `PollerIoDriver`. The suite cancels when BoringSSL is not staged or the host has no poller.
  */
class StartTlsUpgradeTest extends Test:

    import AllowUnsafe.embrace.danger

    private val transportConfig = kyo.net.TransportConfig.default
    private def sock            = Ffi.load[SocketBindings]

    private val serverTls = NetTlsConfig(
        certChainPath = Present(TlsTestCert.certPath),
        privateKeyPath = Present(TlsTestCert.keyPath)
    )
    private val clientTls = NetTlsConfig(trustAll = true)

    private def boringSslAvailable: Boolean =
        try Ffi.load[BoringSslBindings].probeAvailable()
        catch case _: Throwable => false

    private def assumeReady(): Unit =
        if !(PosixConstants.isLinux || PosixConstants.isMacOrBsd) then cancel("STARTTLS test needs epoll (Linux) or kqueue (macOS/BSD)")
        if !boringSslAvailable then cancel("BoringSSL not staged for this host")

    "STARTTLS feeds staged ciphertext with no prefix loss and reuses the same fd" in {
        assumeReady()
        val driver = PollerIoDriver.init(transportConfig)
        discard(driver.start())
        val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        Sync.ensure(Sync.defer(driver.close())) {
            loopbackPair().map { case (clientFd, serverFd) =>
                val clientHandle = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val serverHandle = PosixHandle.socket(serverFd, PosixHandle.DefaultReadBufferSize, Absent)
                val clientPlain  = transport.openWith(clientHandle, driver)
                val serverPlain  = transport.openWith(serverHandle, driver)
                clientPlain.start()
                serverPlain.start()

                val message = "starttls-no-byte-dropped".getBytes("UTF-8")

                // Postgres-style STARTTLS negotiation: the client sends a 1-byte upgrade signal in plaintext, the server reads it, and only
                // then do both sides upgrade. This drains the plaintext pumps' pending reads before the upgrade re-registers the fd, so the
                // handshake read does not race a stale plaintext read.
                val signal = clientPlain.outbound.safe.put(Span.fromUnsafe(Array[Byte]('U'.toByte))).andThen {
                    serverPlain.inbound.safe.take.map(sig =>
                        assert(sig.toArray.sameElements(Array[Byte]('U'.toByte)), "lost the upgrade signal")
                    )
                }

                signal.andThen {
                    // Drive both upgrades concurrently (the handshake is a two-party exchange over the fds).
                    val serverUpgrade = transport.upgradeRole(serverPlain, serverTls, transportConfig.channelCapacity, isServer = true).safe
                    val clientUpgrade =
                        transport.upgradeRole(clientPlain, clientTls, transportConfig.channelCapacity, isServer = false).safe
                    Async.zip(clientUpgrade.get, serverUpgrade.get)
                }.map { case (clientTlsConn, serverTlsConn) =>
                    // fd reuse: the upgraded connections sit on the SAME fds as the plaintext ones.
                    val clientUpFd = clientTlsConn.asInstanceOf[InternalConnection[PosixHandle]].handle.readFd
                    val serverUpFd = serverTlsConn.asInstanceOf[InternalConnection[PosixHandle]].handle.readFd
                    assert(clientUpFd == clientFd, s"client fd churned: $clientFd -> $clientUpFd")
                    assert(serverUpFd == serverFd, s"server fd churned: $serverFd -> $serverUpFd")

                    // no prefix loss: the server decrypts the FULL message the client encrypts.
                    clientTlsConn.outbound.safe.put(Span.fromUnsafe(message)).andThen {
                        collect(serverTlsConn, message.length).map { received =>
                            clientTlsConn.close()
                            serverTlsConn.close()
                            assert(
                                received.sameElements(message),
                                s"decrypted stream mismatch: got '${new String(received, "UTF-8")}'"
                            )
                        }
                    }
                }
            }
        }
    }

    "STARTTLS on a non-upgradable in-memory connection aborts NetNotUpgradableException" in {
        val driver    = PollerIoDriver.init(transportConfig)
        val transport = TestTransports.forTesting(transportConfig, driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        Sync.ensure(Sync.defer(driver.close())) {
            val inbound  = Channel.Unsafe.init[Span[Byte]](8)
            val outbound = Channel.Unsafe.init[Span[Byte]](8)
            val inMem    = InternalConnection.inMemory(inbound, outbound)
            Abort.run[NetException](transport.upgradeToTls(inMem, clientTls, transportConfig.channelCapacity).safe.get).map {
                case Result.Failure(_: NetNotUpgradableException) => succeed
                case other                                        => fail(s"expected NetNotUpgradableException, got $other")
            }
        }
    }

    /** Read from a TLS connection's inbound channel until `target` plaintext bytes have arrived. */
    private def collect(conn: Connection, target: Int)(using Frame): Array[Byte] < (Async & Abort[Closed]) =
        Loop(Array.emptyByteArray) { acc =>
            if acc.length >= target then Loop.done(acc)
            else
                conn.inbound.safe.take.map { chunk =>
                    Loop.continue(acc ++ chunk.toArray)
                }
        }

    /** Build a connected loopback socket pair; returns (clientFd, acceptedFd). */
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
                    // The transport drives these fds through its readiness model, which requires non-blocking sockets: the handshake's
                    // recvAndFeed does a direct non-blocking recv and parks on EAGAIN, and the poll loop's recv/send expect EAGAIN too.
                    // PosixTransport.connect/accept set O_NONBLOCK on every fd they open; this raw loopback pair must do the same, or a recv
                    // with no data blocks the carrier forever (the server's handshake recv would never return, starving the client's upgrade).
                    val shim = Ffi.load[PosixShimBindings]
                    assert(shim.kyo_posix_set_nonblocking(client) == 0, "set client non-blocking")
                    assert(shim.kyo_posix_set_nonblocking(accepted) == 0, "set accepted non-blocking")
                    sock.close(server).safe.get.map(_ => (client, accepted))
                }
            }
        }
    end loopbackPair

end StartTlsUpgradeTest
