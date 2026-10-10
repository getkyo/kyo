package kyo.net.internal

import kyo.*
import kyo.net.NetException
import scala.scalajs.js as sjs

/** The Node transport's stdio connection over test-owned streams ([[StdioStreams]]): the stdin/stdout shim must answer every property the
  * driver reads the way a Node socket would.
  */
class JsTransportStdioTest extends kyo.net.Test:

    import AllowUnsafe.embrace.danger

    private def openStdio(streams: StdioStreams)(using Frame): kyo.net.Connection < (Async & Abort[NetException] & Scope) =
        Sync.defer(JsTransport.init()).map { transport =>
            val driver = transport.pool.next()
            Scope.ensure(Sync.defer(driver.close())).andThen {
                transport.openStdio(streams.stdin, streams.stdout, channelCapacity = 4).safe.get.map { conn =>
                    Scope.ensure(Sync.defer(conn.close())).andThen(conn)
                }
            }
        }

    private def nodeBuffer(text: String): sjs.Dynamic = sjs.Dynamic.global.Buffer.from(text)

    private def text(span: Span[Byte]): String = new String(span.toArray, "UTF-8")

    "a stdio connection reads stdin and writes stdout until stdin ends" in {
        val streams = new StdioStreams
        val written = Promise.Unsafe.init[String, Any]()
        discard(streams.stdout.on(
            "data",
            ((chunk: sjs.Dynamic) => written.completeDiscard(Result.succeed(chunk.toString()))): sjs.Function1[sjs.Dynamic, Unit]
        ))
        for
            conn  <- openStdio(streams)
            _     <- Sync.defer(discard(streams.stdin.write(nodeBuffer("ping"))))
            read  <- conn.inbound.safe.take
            _     <- conn.outbound.safe.put(Span.fromUnsafe("pong".getBytes("UTF-8")))
            out   <- written.safe.get
            _     <- Sync.defer(discard(streams.stdin.end()))
            ended <- Abort.run[Closed](conn.inbound.safe.take)
        yield
            assert(text(read) == "ping")
            assert(out == "pong")
            assert(ended.isFailure, s"stdin's end must end inbound, got $ended")
        end for
    }

    "a stdio connection over a stdin that already ended reports end of stream" in {
        val streams = new StdioStreams
        val ended   = Promise.Unsafe.init[Unit, Any]()
        discard(streams.stdin.once("end", (() => ended.completeDiscard(Result.succeed(()))): sjs.Function0[Unit]))
        discard(streams.stdin.end())
        discard(streams.stdin.resume())
        for
            _    <- ended.safe.get
            conn <- openStdio(streams)
            // A stream that already emitted 'end' emits nothing more, so a read that misses the ended state parks forever; the bound turns
            // that hang into a failure.
            outcome <- Abort.run[Timeout | Closed](Async.timeout(10.seconds)(conn.inbound.safe.take))
        yield assert(
            outcome match
                case Result.Failure(_: Closed) => true
                case _                         => false
            ,
            s"a read on an ended stdin must end inbound, got $outcome"
        )
        end for
    }

    "a stdio connection over a stdin destroyed before it ended fails the read Closed" in {
        val streams = new StdioStreams
        val closed  = Promise.Unsafe.init[Unit, Any]()
        discard(streams.stdin.once("close", (() => closed.completeDiscard(Result.succeed(()))): sjs.Function0[Unit]))
        discard(streams.stdin.destroy())
        for
            _    <- closed.safe.get
            conn <- openStdio(streams)
            // A destroyed stream emits nothing more, so a read that misses the aborted state parks forever; the bound turns that hang into a
            // failure.
            outcome <- Abort.run[Timeout | Closed](Async.timeout(10.seconds)(conn.inbound.safe.take))
        yield assert(
            outcome match
                case Result.Failure(_: Closed) => true
                case _                         => false
            ,
            s"a read on a stdin destroyed before its end must end inbound, got $outcome"
        )
        end for
    }

end JsTransportStdioTest
