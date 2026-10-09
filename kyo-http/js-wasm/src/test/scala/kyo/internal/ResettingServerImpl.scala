package kyo.internal

import kyo.*
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("node:net", JSImport.Namespace)
private object ResettingServerNet extends js.Object:
    def createServer(listener: js.Function1[js.Dynamic, Unit]): js.Dynamic = js.native
end ResettingServerNet

private[kyo] trait ResettingServerImpl extends ResettingServer:

    def resettingServer(response: Array[Byte], reset: Latch)(using Frame): Int < (Async & Scope) =
        Sync.Unsafe.defer {
            val listening = Promise.Unsafe.init[Int, Any]()
            val written   = Promise.Unsafe.init[js.Dynamic, Any]()
            val bytes     = js.Dynamic.global.Buffer.from(js.Array(response.map(b => (b & 0xff): Int)*))
            val server    = ResettingServerNet.createServer { (socket: js.Dynamic) =>
                val head = new java.lang.StringBuilder
                discard(socket.on("error", (_: js.Dynamic) => ()))
                discard(socket.on(
                    "data",
                    { (chunk: js.Dynamic) =>
                        if head.indexOf("\r\n\r\n") < 0 then
                            discard(head.append(chunk.applyDynamic("toString")("latin1").asInstanceOf[String]))
                            if head.indexOf("\r\n\r\n") >= 0 then
                                discard(socket.write(bytes, (() => written.completeDiscard(Result.succeed(socket))): js.Function0[Unit]))
                    }: js.Function1[js.Dynamic, Unit]
                ))
            }
            discard(server.listen(
                0,
                "127.0.0.1",
                (() => listening.completeDiscard(Result.succeed(server.address().port.asInstanceOf[Int]))): js.Function0[Unit]
            ))
            val serve = written.safe.get.map(socket => reset.await.andThen(Sync.defer(discard(socket.resetAndDestroy()))))
            Scope.ensure(Sync.defer(discard(server.close()))).andThen(Fiber.init(serve)).andThen(listening.safe.get)
        }

end ResettingServerImpl
