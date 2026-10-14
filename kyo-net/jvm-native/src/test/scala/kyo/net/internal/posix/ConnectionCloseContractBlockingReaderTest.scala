package kyo.net.internal.posix

import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.Test

/** The close contract's C5 on the blocking-reader connection, the epoll regular-file stdin fallback: a close while its `read(2)` is in flight
  * releases the handle once, after the read returns, and never delivers what that read brings.
  *
  * The connection reads a blocking loopback socket through a [[BlockingReaderDriver]] over [[RecordingSocketBindings]], so the leaf sees
  * the read enter `read(2)`, sees it return, and sees whether the read buffer was freed while it still held it. The peer withholds bytes
  * until the read is in flight, the close runs, and then one byte ends the read.
  */
class ConnectionCloseContractBlockingReaderTest extends Test:

    import AllowUnsafe.embrace.danger

    private def sock = Ffi.load[SocketBindings]

    "C5 > a close while a blocking read is in flight frees the handle once, after the read returns" in {
        PosixTestSockets.assumePoller()
        val spy       = RecordingSocketBindings(sock)
        val backend   = PollerBackend.default()
        val poller    = TestDrivers.forBackend(backend, backend.create(), spy)
        val loop      = poller.start()
        val reader    = TestDrivers.blockingReader(poller, spy)
        val transport = TestTransports.forTesting(poller, spy, backendIsEpoll = PosixConstants.isLinux)
        Scope.run(Scope.ensure(Sync.defer(poller.close()).andThen(loop.safe.get)).andThen {
            PosixTestSockets.blockingLoopbackPair().map { (connRead, peerWrite) =>
                PosixTestSockets.loopbackPair().map { (connWrite, peerRead) =>
                    // The connection never closes a split handle's descriptors, so the leaf closes all four once the driver has stopped.
                    Sync.ensure(Sync.defer(Seq(connRead, peerWrite, connWrite, peerRead).foreach(fd => discard(sock.close(fd))))) {
                        val entered = spy.readEntered(connRead)
                        val conn    = TestTransports.splitConnectionOver(transport, reader, connRead, connWrite, 16)
                        entered.safe.get.andThen {
                            val returned = spy.readReturned(connRead)
                            conn.close()
                            val one = Buffer.fromArray[Byte](Array[Byte](7))
                            Sync.ensure(Sync.defer(one.close()))(sock.send(peerWrite, one, 1L, 0).safe.get)
                                .andThen(returned.safe.get)
                                .andThen(Abort.run[Closed](conn.inbound.safe.take))
                                .map { outcome =>
                                    assert(
                                        !spy.readIntoFreedBuffer.get(),
                                        "the close freed the read buffer while read(2) still held it"
                                    )
                                    assert(
                                        outcome match
                                            case Result.Failure(_: Closed) => true
                                            case _                         => false
                                        ,
                                        s"the read in flight at the close must end Closed and never deliver its byte, got $outcome"
                                    )
                                }
                        }
                    }
                }
            }
        })
    }

end ConnectionCloseContractBlockingReaderTest
