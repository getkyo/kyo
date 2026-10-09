package kyo.net.internal.posix

import java.lang.foreign.MemorySegment
import java.util.concurrent.atomic.AtomicReference
import kyo.*
import kyo.ffi.Buffer
import kyo.ffi.Ffi
import kyo.net.NetConfig
import kyo.net.NetException
import kyo.net.NetTlsConfig
import kyo.net.Test
import kyo.net.internal.TlsProviderPlatform
import kyo.net.internal.TlsTestCert
import scala.jdk.CollectionConverters.*

/** Per-call off-heap scratch on the posix transport lives in a thread-confined arena.
  *
  * On the JVM a `Buffer.alloc` buffer is backed by a shared `Arena`, and closing a shared arena runs a handshake with every platform thread,
  * so a buffer allocated and freed per syscall or per handshake record made connection setup scale with the JVM's thread count. A confined
  * buffer is not accessible from any thread other than its owner, which is what these leaves observe through the recording decorators: every
  * scratch buffer handed to a per-call socket syscall, and every buffer handed to the client TLS engine during the handshake that is not one
  * of the connection's own reused buffers, must be inaccessible from a foreign thread. JVM-only: Native and JS have no arena kinds, so the
  * property does not exist there.
  */
class PosixTransportScratchArenaTest extends Test:

    import AllowUnsafe.embrace.danger

    // Never started: isAccessibleBy compares the segment's owner thread against it, so any thread that is not the owner serves.
    private val foreignThread = new Thread(() => ())

    private def confined(buf: Buffer[?]): Boolean =
        (buf.raw: Any) match
            case seg: MemorySegment => !seg.isAccessibleBy(foreignThread)
            case other              => throw new IllegalStateException(s"JVM Buffer not backed by a MemorySegment: $other")

    private def withTransport[A](sockets: SocketBindings, engines: PosixTransport.TlsEngineFactory)(
        body: PosixTransport => A < (Async & Abort[NetException | Closed] & Scope)
    )(using Frame): A < (Async & Abort[NetException | Closed] & Scope) =
        val driver     = PollerIoDriver.init()
        val transport  = TestTransports.forTesting(driver, sockets, backendIsEpoll = PosixConstants.isLinux, buildEngine = engines)
        val driverDone = driver.start()
        Abort.run[NetException | Closed](Scope.run(body(transport))).map { result =>
            Sync.defer(driver.close()).andThen(Abort.run(driverDone.safe.get).unit).andThen(Abort.get(result))
        }
    end withTransport

    "socket options, getsockname and the accept drain use confined scratch" in {
        PosixTestSockets.assumePoller()
        val sockets  = new RecordingSocketBindings(Ffi.load[SocketBindings])
        val accepted = Promise.Unsafe.init[kyo.net.Connection, Any]()
        // Explicit socket buffer sizes add the SO_RCVBUF / SO_SNDBUF setsockopt calls on both ends.
        val config = NetConfig.default.copy(soRcvBuf = Present(64.kib), soSndBuf = Present(64.kib))
        withTransport(sockets, PosixTransport.realEngineFactory) { transport =>
            for
                listener <- transport.listen("127.0.0.1", 0, 16, config)(c => accepted.completeDiscard(Result.succeed(c))).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                client   <- transport.connect("127.0.0.1", listener.port, 10.seconds, config).safe.get
                _        <- Scope.ensure(Sync.defer(client.close()))
                server   <- accepted.safe.get
                _        <- Scope.ensure(Sync.defer(server.close()))
            yield
                val recorded = sockets.scratchBufs.iterator().asScala.toList
                val calls    = recorded.map(_._1).toSet
                assert(
                    Set("setsockopt", "getsockname", "acceptNow").subsetOf(calls),
                    s"expected setsockopt, getsockname and acceptNow scratch, recorded: $calls"
                )
                val shared = recorded.filterNot((_, buf) => confined(buf)).map(_._1)
                assert(shared.isEmpty, s"per-call scratch allocated in a shared arena for: $shared")
        }
    }

    "the client TLS handshake feeds and drains the engine through confined scratch" in {
        PosixTestSockets.assumePoller()
        val clientTls = NetTlsConfig(trustAll = true)
        val serverTls = NetTlsConfig(certChainPath = Present(TlsTestCert.certPath), privateKeyPath = Present(TlsTestCert.keyPath))
        val available =
            try
                discard(TlsProviderPlatform.engine(clientTls, "localhost", isServer = false))
                true
            catch case _: Throwable => false
        if !available then cancel("No TLS provider staged for this host")
        val clientEngine                             = new AtomicReference[RecordingTlsEngine]()
        val engines: PosixTransport.TlsEngineFactory = (cfg, host, isServer) =>
            val real = PosixTransport.realEngineFactory(cfg, host, isServer)
            if isServer then real
            else
                val recording = new RecordingTlsEngine(real)
                clientEngine.set(recording)
                recording
            end if
        withTransport(Ffi.load[SocketBindings], engines) { transport =>
            for
                listener <- transport.listenTls("127.0.0.1", 0, 16, serverTls, NetConfig.default)(c => c.close()).safe.get
                _        <- Scope.ensure(Sync.defer(listener.close()))
                client   <- transport.connectTls("127.0.0.1", listener.port, clientTls, 10.seconds, NetConfig.default).safe.get
            yield
                // The connection's own reused buffers are legitimately shared (they cross carriers), and a post-handshake record can reach the
                // engine through them before this snapshot. Read them before close frees them.
                val handle                 = client.asInstanceOf[kyo.net.internal.transport.Connection[PosixHandle]].handle
                val owned: List[Buffer[?]] =
                    List(handle.recvStaging, handle.decryptDrain, handle.encryptDrain, handle.plaintextStaging, handle.flushMirror)
                        .flatMap(_.toList) :+ handle.readBuffer
                val engine = clientEngine.get()
                val fed    = engine.feedBufs.iterator().asScala.toList
                val read   = engine.readPlainBufs.iterator().asScala.toList
                val sent   = engine.drainCipherBufs.iterator().asScala.toList
                client.close()
                def scratchOf(bufs: List[Buffer[Byte]]): List[Buffer[Byte]] = bufs.filterNot(b => owned.exists(_ eq b))
                assert(scratchOf(fed).nonEmpty, "the handshake fed no heap ciphertext to the engine")
                assert(scratchOf(sent).nonEmpty, "the handshake drained no ciphertext from the engine")
                val shared = List("feedCiphertext" -> fed, "readPlain" -> read, "drainCiphertext" -> sent).flatMap { (call, bufs) =>
                    scratchOf(bufs).filterNot(confined).map(_ => call)
                }
                assert(shared.isEmpty, s"handshake scratch allocated in a shared arena for: $shared")
        }
    }

end PosixTransportScratchArenaTest
