package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Ffi
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test

/** The one-upgrade-per-handle contract: `PosixTransport.upgradeRole` rejects a second `upgradeToTls` of an already-upgraded handle. The
  * upgraded connection is a fresh Connection over the SAME handle (its own `claimUpgrade` would admit it), but the handle's upgrade
  * machinery is single-shot, and reopening the window with the durable `isUpgraded` marker already set would let the drivers' stray-arm
  * re-check fire before the new upgrade's state CAS (the pre-CAS abort hazard the stranded-read fixes are built to exclude). The reject
  * must be typed and fast, before any detach touches the connection.
  */
class PosixTransportUpgradeRejectTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    /** A no-op engine standing in for an attached TLS engine: the reject fires on tls.isDefined before any engine call, so no behavior is
      * needed beyond existing.
      */
    final private class StubTlsEngine extends kyo.net.internal.TlsEngine:
        def handshakeStep()(using AllowUnsafe): Int                                      = 0
        def feedCiphertext(buf: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Int  = 0
        def drainCiphertext(buf: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Int = 0
        def readPlain(buf: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Int       = 0
        def writePlain(buf: kyo.ffi.Buffer[Byte], len: Int)(using AllowUnsafe): Int      = 0
        def hasBufferedPlaintext(using AllowUnsafe): Boolean                             = false
        def readBuffered()(using AllowUnsafe): Span[Byte]                                = Span.empty
        def certSha256()(using AllowUnsafe): Maybe[Span[Byte]]                           = Absent
        def shutdownStep()(using AllowUnsafe): Int                                       = 0
        def free()(using AllowUnsafe): Unit                                              = ()
    end StubTlsEngine

    "an upgradeToTls on a connection whose handle already carries an engine fails typed" in {
        PosixTestSockets.assumePoller()
        val driver    = PollerIoDriver.init()
        val transport = TestTransports.forTesting(driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        discard(driver.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            Sync.ensure(Sync.defer {
                discard(sock.close(peerFd))
                driver.close()
            }) {
                val handle    = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val plaintext = transport.openWith(handle, driver, channelCapacity = 4)
                plaintext.start()
                // The connectTls/listenTls shape: the handle is born with an engine and the connection's upgradeFn is wired anyway. A
                // raw-mode STARTTLS over engine-routed reads must be rejected typed.
                handle.tls = Present(new StubTlsEngine)
                Abort.run[NetException | Timeout](
                    Async.timeout(5.seconds)(transport.upgradeToTls(plaintext, NetTlsConfig(trustAll = true), 4).safe.get)
                ).map { second =>
                    plaintext.close()
                    second match
                        case Result.Failure(_: NetException) => succeed
                        case Result.Failure(_: Timeout) =>
                            assert(false, "the engine-carrying re-upgrade hung instead of failing typed (the reject is missing)")
                        case other =>
                            assert(false, s"an upgrade over an engine-carrying handle must fail typed; got $other")
                    end match
                }
            }
        }
    }

    "a second upgradeToTls on an already-upgraded handle fails typed" in {
        PosixTestSockets.assumePoller()
        val driver    = PollerIoDriver.init()
        val transport = TestTransports.forTesting(driver, Ffi.load[SocketBindings], backendIsEpoll = false)
        discard(driver.start())
        PosixTestSockets.loopbackPair().map { case (clientFd, peerFd) =>
            Sync.ensure(Sync.defer {
                discard(sock.close(peerFd))
                driver.close()
            }) {
                val handle    = PosixHandle.socket(clientFd, PosixHandle.DefaultReadBufferSize, Absent)
                val plaintext = transport.openWith(handle, driver, channelCapacity = 4)
                plaintext.start()
                // The post-first-upgrade state: the upgraded connection reuses the handle, whose isUpgraded marker is durable.
                handle.isUpgraded = true
                Abort.run[NetException | Timeout](
                    Async.timeout(5.seconds)(transport.upgradeToTls(plaintext, NetTlsConfig(trustAll = true), 4).safe.get)
                ).map { second =>
                    plaintext.close()
                    second match
                        case Result.Failure(_: NetException) => succeed
                        case Result.Failure(_: Timeout) =>
                            assert(false, "the re-upgrade hung instead of failing typed (the reject is missing)")
                        case other =>
                            assert(false, s"a second upgrade of an upgraded handle must fail typed; got $other")
                    end match
                }
            }
        }
    }

end PosixTransportUpgradeRejectTest
