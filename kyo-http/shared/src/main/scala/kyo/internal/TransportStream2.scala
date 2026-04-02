package kyo.internal

import kyo.*

trait TransportStream2:
    def read(using Frame): Stream[Span[Byte], Async]
    def write(data: Span[Byte])(using Frame): Unit < Async

    /** Bridge to old TransportStream API for WsCodec compatibility.
      *
      * Uses lazy on-demand pulling: each read(out) call directly pulls one chunk from the underlying stream via take(1).run instead of
      * using a background fiber. This avoids scheduling starvation on single-threaded runtimes (e.g. Scala Native) where an extra polling
      * fiber would starve other fibers.
      */
    def asTransportStream(using Frame): TransportStream < Sync =
        Sync.defer {
            new TransportStream:
                private var leftover: Span[Byte] = Span.empty[Byte]

                def read(out: Array[Byte])(using Frame): Int < Async =
                    if leftover.nonEmpty then
                        val take = math.min(leftover.size, out.length)
                        discard(leftover.copyToArray(out, 0, take))
                        leftover = leftover.slice(take, leftover.size)
                        take
                    else
                        TransportStream2.this.read.take(1).run.map { chunk =>
                            if chunk.isEmpty then -1
                            else
                                val span = chunk(0)
                                if span.isEmpty then read(out)
                                else
                                    val take = math.min(span.size, out.length)
                                    discard(span.copyToArray(out, 0, take))
                                    if take < span.size then
                                        leftover = span.slice(take, span.size)
                                    take
                                end if
                        }

                def write(data: Span[Byte])(using Frame): Unit < Async =
                    TransportStream2.this.write(data)
        }
end TransportStream2

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
