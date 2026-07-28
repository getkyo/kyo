package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsProviderPlatform
import kyo.net.internal.TlsTestCert

/** The handshake registry must not retain a settled obligation.
  *
  * An accepted handshake registers a teardown obligation and arms its deadline. The deadline is armed BEFORE the registration token exists,
  * so its cleanup reads that token through a ref: a timer firing in the window between arming and publishing reads 0 and removes nothing,
  * leaving an entry whose exactly-once gate the timer already spent. No later discharge can fire it, and on this transport nothing else
  * reclaims it, since a transport is process-lifetime and the entry's owning listener can outlive it indefinitely.
  *
  * A tight deadline against a peer that never sends a ClientHello lands in that window often, so the leaf drives many handshakes and then
  * requires the registry to drain to empty. Retention only, never a double-free: the spent gate is what makes the entry inert.
  */
class PosixTransportHandshakeRegistryTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock(using AllowUnsafe) = Ffi.load[SocketBindings]

    /** Open a raw client socket connected to `port` on loopback. The client then sends NOTHING, so the server-side handshake it triggers
      * parks waiting for a ClientHello and is reaped by the deadline.
      */
    private def connectRaw(port: Int)(using Frame, AllowUnsafe, kyo.test.AssertScope): Int < Async =
        val client   = sock.socket(PosixConstants.AF_INET, PosixConstants.SOCK_STREAM, 0).value
        val (ca, cl) = SockAddr.encodeInet4(PosixConstants.AF_INET, "127.0.0.1", port).getOrElse(fail("encode failed"))
        Sync.ensure(Sync.defer(ca.close()))(sock.connect(client, ca, cl).safe.get.map(r => assert(r.value == 0))).map(_ => client)
    end connectRaw

    "PosixTransport handshake registry" - {

        "every settled accept handshake leaves the registry, including one whose deadline fires during registration" in {
            PosixTestSockets.assumePoller()
            if !TlsProviderPlatform.registered.exists(_.isAvailable) then cancel("no TLS provider available on this backend")
            given Frame = Frame.internal
            val backend = PollerBackend.default()
            val driver  = TestDrivers.forBackend(backend, backend.create(), sock)
            discard(driver.start())
            val transport = TestTransports.forTesting(driver, sock, backendIsEpoll = false)
            // 1ms: short enough that the timer routinely fires inside the arm-then-publish window this leaf exists for.
            val serverTls = NetTlsConfig(
                certChainPath = Present(TlsTestCert.certPath),
                privateKeyPath = Present(TlsTestCert.keyPath),
                handshakeTimeout = 1.milli
            )
            transport.listenTls("127.0.0.1", 0, 64, serverTls)(_ => ()).safe.get.map { listener =>
                // Each client completes the TCP accept and then sends nothing, so every server handshake parks and is reaped by the deadline.
                Loop(0) { i =>
                    if i >= 40 then Loop.done(())
                    else
                        connectRaw(listener.port).map { clientFd =>
                            // Hold briefly so the 1ms deadline fires against a parked handshake, then release the client fd.
                            Async.sleep(3.millis).andThen(Sync.defer(discard(sock.close(clientFd)))).andThen(Loop.continue(i + 1))
                        }
                }.andThen {
                    // Every handshake has settled by its deadline; the registry must hold nothing for them.
                    // assertEventually fails the leaf if the count never reaches 0; the follow-up assert states what a non-zero count means.
                    assertEventually(Sync.defer(transport.pendingHandshakeCount == 0)).map { _ =>
                        val remaining = transport.pendingHandshakeCount
                        listener.close()
                        driver.close()
                        assert(
                            remaining == 0,
                            s"the handshake registry retained $remaining settled obligation(s): an entry whose gate the deadline already " +
                                "spent can never be discharged, and nothing else reclaims it on a process-lifetime transport"
                        )
                    }
                }
            }
        }
    }

end PosixTransportHandshakeRegistryTest
