package kyo.internal

import kyo.*

trait TransportStream:
    def read(using Frame): Stream[Span[Byte], Async]
    def write(data: Span[Byte])(using Frame): Unit < Async
end TransportStream

trait Transport:
    type Connection <: TransportStream
    def connect(address: HttpAddress, tls: Maybe[TlsConfig])(using Frame): Connection < (Async & Abort[HttpException])
    def listen(address: HttpAddress, backlog: Int, tls: Maybe[TlsConfig])(using Frame): TransportListener[Connection] < Async
    def isAlive(c: Connection)(using Frame): Boolean < Sync
    def closeNow(c: Connection)(using Frame): Unit < Async
    def close(c: Connection, gracePeriod: Duration)(using Frame): Unit < Async

    inline def connectWith[A, S](address: HttpAddress, tls: Maybe[TlsConfig])(
        inline f: Connection => A < S
    )(using inline frame: Frame): A < (S & Async & Abort[HttpException]) =
        connect(address, tls).map(f)

    inline def listenWith[A, S](address: HttpAddress, backlog: Int, tls: Maybe[TlsConfig])(
        inline f: TransportListener[Connection] => A < S
    )(using inline frame: Frame): A < (S & Async) =
        listen(address, backlog, tls).map(f)
end Transport

class TransportListener[+C <: TransportStream](
    val address: HttpAddress,
    val connections: Stream[C, Async],
    val close: Unit < Sync = Kyo.unit
)
