package kyo.internal

import kyo.{WebSocketFrame as WsFrame, *}
import scala.scalanative.runtime.fromRawPtr
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*

/** Native WebSocket client using wslay (frame codec) + POSIX sockets (transport).
  *
  * The C layer (h2o_wrappers.c) handles TCP connect, HTTP upgrade handshake, and wslay initialization. This Scala layer manages the kyo
  * Channel integration and fiber lifecycle.
  */
final private[kyo] class NativeWebSocketClientBackend extends HttpBackend.WebSocketClient:

    def connect[A, S](
        host: String,
        port: Int,
        path: String,
        ssl: Boolean,
        headers: HttpHeaders,
        config: WebSocketConfig
    )(
        f: WebSocket => A < S
    )(using Frame): A < (S & Async & Abort[HttpException]) =
        if ssl then Abort.fail(HttpConnectException(host, port, new Exception("WSS not yet supported on native")))
        else
            Sync.Unsafe.defer {
                Zone {
                    val inbound  = Channel.Unsafe.init[WsFrame](config.bufferSize)
                    val outbound = Channel.Unsafe.init[WsFrame](config.bufferSize)
                    val closeRef = AtomicRef.Unsafe.init[Maybe[(Int, String)]](Absent)

                    // Build extra headers string for the C handshake
                    val headerSb = new StringBuilder
                    headers.foreach((name, value) => headerSb.append(s"$name: $value\r\n"))
                    val extraHeaders = toCString(headerSb.toString)

                    // Register state in global registry for static C callbacks
                    val wsId  = WsRegistry.register(inbound, outbound, closeRef)
                    val idPtr = fromRawPtr[Byte](scala.scalanative.runtime.Intrinsics.castLongToRawPtr(wsId))

                    val client = H2oBindings.wsClientConnect(
                        toCString(host),
                        port,
                        toCString(path),
                        extraHeaders,
                        idPtr,
                        WsRegistry.msgCallback,
                        WsRegistry.closeCallback
                    )

                    if client == null then
                        Abort.fail(HttpConnectException(host, port, new Exception("WebSocket connect failed")))
                    else
                        val kyoCloseFn: (Int, String) => Unit < Async = (code, reason) =>
                            Sync.Unsafe.defer {
                                closeRef.set(Present((code, reason)))
                                discard(H2oBindings.wsClientClose(client, code))
                            }

                        val ws = new WebSocket(inbound.safe, outbound.safe, closeRef.safe, kyoCloseFn)

                        // Receive thread — polls wslay for incoming frames
                        @volatile var running = true
                        val recvThread = new Thread(
                            () =>
                                while running do
                                    import AllowUnsafe.embrace.danger
                                    val rc = H2oBindings.wsClientService(client)
                                    if rc != 0 then
                                        running = false
                                        discard(inbound.close())
                                        discard(outbound.close())
                                    else
                                        Thread.sleep(1) // Brief yield when no data
                                    end if
                            ,
                            "native-ws-recv"
                        )
                        recvThread.setDaemon(true)
                        recvThread.start()

                        // Drain fiber for outbound
                        val drainFiber = Sync.Unsafe.evalOrThrow(Fiber.initUnscoped {
                            Sync.Unsafe.ensure {
                                running = false
                                discard(inbound.close())
                                discard(outbound.close())
                            } {
                                Loop.foreach {
                                    outbound.safe.takeWith { frame =>
                                        Sync.Unsafe.defer {
                                            Zone {
                                                frame match
                                                    case WsFrame.Text(data) =>
                                                        val bytes = data.getBytes("UTF-8")
                                                        val ptr   = stackalloc[Byte](bytes.length)
                                                        var i     = 0
                                                        while i < bytes.length do
                                                            ptr(i) = bytes(i)
                                                            i += 1
                                                        discard(H2oBindings.wsClientSend(client, 1, ptr, bytes.length))
                                                    case WsFrame.Binary(data) =>
                                                        val arr = data.toArrayUnsafe
                                                        val ptr = stackalloc[Byte](arr.length)
                                                        var i   = 0
                                                        while i < arr.length do
                                                            ptr(i) = arr(i)
                                                            i += 1
                                                        discard(H2oBindings.wsClientSend(client, 2, ptr, arr.length))
                                            }
                                            Loop.continue
                                        }
                                    }
                                }.handle(Abort.run[Closed]).unit
                            }
                        })

                        // Run user function, cleanup on exit
                        Sync.ensure {
                            Sync.Unsafe.defer {
                                running = false
                                discard(H2oBindings.wsClientClose(client, 1000))
                                discard(inbound.close())
                                discard(outbound.close())
                                discard(drainFiber.unsafe.interrupt(Result.Panic(new Exception("closing"))))
                                H2oBindings.wsClientFree(client)
                                WsRegistry.unregister(wsId)
                            }
                        } {
                            f(ws)
                        }
                    end if
                }
            }

end NativeWebSocketClientBackend
