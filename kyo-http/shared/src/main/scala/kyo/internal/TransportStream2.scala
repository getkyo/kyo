package kyo.internal

import kyo.*

trait TransportStream2:
    def read(using Frame): Stream[Span[Byte], Async]
    def write(data: Span[Byte])(using Frame): Unit < Async

trait Transport2:
    type Connection <: TransportStream2
    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using
        Frame
    )
        : Connection < (Async & Abort[HttpException])
    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using
        Frame
    )
        : TransportListener2[Connection] < (Async & Scope)
    def isAlive(c: Connection)(using Frame): Boolean < Sync
    def closeNow(c: Connection)(using Frame): Unit < Sync
    def close(c: Connection, gracePeriod: Duration)(using Frame): Unit < Async
end Transport2

class TransportListener2[+C <: TransportStream2](
    val port: Int,
    val host: String,
    val connections: Stream[C, Async],
    val close: Unit < Sync = Kyo.unit
)
