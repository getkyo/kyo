package kyo.internal

import kyo.*

trait TransportStream:
    def read(using Frame): Stream[Span[Byte], Async]
    def write(data: Span[Byte])(using Frame): Unit < Async
end TransportStream

trait Transport:
    type Connection <: TransportStream
    def connect(host: String, port: Int, tls: Maybe[TlsConfig])(using
        Frame
    )
        : Connection < (Async & Abort[HttpException])
    def listen(host: String, port: Int, backlog: Int, tls: Maybe[TlsConfig])(using
        Frame
    )
        : TransportListener[Connection] < (Async & Scope)
    def isAlive(c: Connection)(using Frame): Boolean < Sync
    def closeNow(c: Connection)(using Frame): Unit < Sync
    def close(c: Connection, gracePeriod: Duration)(using Frame): Unit < Async
end Transport

class TransportListener[+C <: TransportStream](
    val port: Int,
    val host: String,
    val connections: Stream[C, Async],
    val close: Unit < Sync = Kyo.unit
)
