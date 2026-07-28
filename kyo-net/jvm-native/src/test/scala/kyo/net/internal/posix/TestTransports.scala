package kyo.net.internal.posix

import kyo.*
import kyo.net.internal.transport.IoDriver
import kyo.net.internal.transport.IoDriverPool

/** Test-tree construction helper for [[PosixTransport]]. It wraps the transport's package-private dependency constructor so a test can build a
  * transport over a caller-supplied driver, socket bindings (the real bindings or a recording decorator), epoll flag, and TLS-engine factory,
  * without any production test factory. Production builds the transport via [[PosixTransport.init]]; this helper lives entirely in the test tree.
  */
object TestTransports:

    /** Build a [[PosixTransport]] over a caller-supplied driver, bindings, epoll flag, and TLS-engine factory. Wraps the single driver in a
      * one-element pool so the test exercises the same constructor the production path uses. Tests use this to inject a recording `fstat` and a
      * chosen backend so the driver-selection matrix is exercised deterministically without a real epoll/io_uring/kqueue host, and to substitute
      * a fake/recording [[kyo.net.internal.TlsEngine]] for the handshake engine (`buildEngine`, defaulting to the real
      * [[PosixTransport.realEngineFactory]]) where a test needs to observe or script the engine the transport is otherwise the sole builder of.
      */
    def forTesting(
        ioDriver: IoDriver[PosixHandle],
        sockets: SocketBindings,
        backendIsEpoll: Boolean,
        buildEngine: PosixTransport.TlsEngineFactory = PosixTransport.realEngineFactory
    )(using AllowUnsafe): PosixTransport =
        val pool = IoDriverPool.init(Array(ioDriver))
        PosixTransport.init(pool, ioDriver, sockets, backendIsEpoll, buildEngine)
    end forTesting

end TestTransports
