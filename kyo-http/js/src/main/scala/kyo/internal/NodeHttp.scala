package kyo.internal

import scala.scalajs.js
import scala.scalajs.js.annotation.*
import scala.scalajs.js.typedarray.Uint8Array

/** Typed Scala.js facades for the Node.js `http` module. */
@js.native
@JSImport("node:http", JSImport.Namespace)
object NodeHttp extends js.Object:
    def createServer(options: js.Object, requestListener: js.Function2[IncomingMessage, ServerResponse, Unit]): NodeHttpServerJs =
        js.native
    def createServer(requestListener: js.Function2[IncomingMessage, ServerResponse, Unit]): NodeHttpServerJs = js.native
end NodeHttp

/** Node.js http.Server instance. */
@js.native
trait NodeHttpServerJs extends js.Object:
    def listen(port: Int, host: String, backlog: Int, callback: js.Function0[Unit]): NodeHttpServerJs = js.native
    def close(callback: js.Function0[Unit]): NodeHttpServerJs                                         = js.native
    def closeAllConnections(): Unit                                                                   = js.native
    def closeIdleConnections(): Unit                                                                  = js.native
    def address(): js.Dynamic                                                                         = js.native
    def on(event: String, listener: js.Function1[js.Dynamic, Unit]): NodeHttpServerJs                 = js.native
    @JSName("on")
    def onUpgrade(
        event: String,
        listener: js.Function3[IncomingMessage, NetSocket, Uint8Array, Unit]
    ): NodeHttpServerJs = js.native
end NodeHttpServerJs

/** Node.js http.IncomingMessage (request). Extends Readable stream. */
@js.native
trait IncomingMessage extends js.Object:
    val method: String                                                   = js.native
    val url: String                                                      = js.native
    val headers: js.Dictionary[js.Any]                                   = js.native
    def on(event: String, listener: js.Function0[Unit]): IncomingMessage = js.native
    @JSName("on")
    def onData(event: String, listener: js.Function1[Uint8Array, Unit]): IncomingMessage = js.native
    def pause(): IncomingMessage                                                         = js.native
    def resume(): IncomingMessage                                                        = js.native
end IncomingMessage

/** Node.js http.ServerResponse. */
@js.native
trait ServerResponse extends js.Object:
    def writeHead(statusCode: Int, headers: js.Dictionary[js.Any]): ServerResponse = js.native
    def write(chunk: Uint8Array): Boolean                                          = js.native
    def end(data: Uint8Array): Unit                                                = js.native
    @JSName("end")
    def endEmpty(): Unit                                                  = js.native
    def on(event: String, listener: js.Function0[Unit]): ServerResponse   = js.native
    def once(event: String, listener: js.Function0[Unit]): ServerResponse = js.native
end ServerResponse

/** Node.js net.Socket — raw TCP socket from HTTP upgrade events. */
@js.native
trait NetSocket extends js.Object:
    def write(data: String): Boolean                               = js.native
    def write(data: Uint8Array): Boolean                           = js.native
    def end(): Unit                                                = js.native
    def destroy(): Unit                                            = js.native
    def on(event: String, listener: js.Function0[Unit]): NetSocket = js.native
    @JSName("on")
    def onData(event: String, listener: js.Function1[Uint8Array, Unit]): NetSocket = js.native
    def pause(): NetSocket                                                         = js.native
    def resume(): NetSocket                                                        = js.native
    def setNoDelay(noDelay: Boolean): NetSocket                                    = js.native
    def setTimeout(timeout: Int): NetSocket                                        = js.native
    def unshift(chunk: Uint8Array): Unit                                           = js.native
end NetSocket
