package kyo.net

import kyo.*

/** Test-only stub [[Connection]] factory.
  *
  * Lives in the `kyo.net` package so it can implement `private[net] def start()`.
  */
object StubConnection:

    /** Creates an in-memory [[Connection]] suitable for unit tests that exercise channel state.
      *
      * Both `inbound` and `outbound` are backed by real `Channel.Unsafe[Span[Byte]]` instances with a large capacity so that `put` never
      * blocks during tests. `AllowUnsafe.embrace.danger` is narrowly scoped here — test code only.
      */
    def apply()(using Frame): Connection =
        import AllowUnsafe.embrace.danger
        val _inbound: Channel.Unsafe[Span[Byte]]  = Channel.Unsafe.init[Span[Byte]](capacity = 1024)
        val _outbound: Channel.Unsafe[Span[Byte]] = Channel.Unsafe.init[Span[Byte]](capacity = 1024)
        val closing                               = Promise.Unsafe.init[Unit, Any]()
        new Connection:
            def inbound: Channel.Unsafe[Span[Byte]]  = _inbound
            def outbound: Channel.Unsafe[Span[Byte]] = _outbound
            def isOpen(using AllowUnsafe): Boolean   = !_outbound.closed()
            def close()(using AllowUnsafe, Frame): Unit =
                discard(_inbound.close())
                discard(_outbound.close())
                closing.completeDiscard(Result.succeed(()))
            end close
            private[kyo] def onClosing: Fiber.Unsafe[Unit, Any] =
                closing
            def detachForUpgrade()(using AllowUnsafe, Frame): Maybe[Chunk[Span[Byte]]] = Maybe.Absent
            private[net] def start()(using AllowUnsafe, Frame): Boolean                = true
            def serverCertificateHash: Maybe[Span[Byte]]                               = Maybe.Absent
            def status: Connection.Status                                              = Connection.Status.Active
        end new
    end apply

end StubConnection
