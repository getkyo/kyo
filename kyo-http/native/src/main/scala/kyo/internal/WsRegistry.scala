package kyo.internal

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kyo.*
import scala.scalanative.runtime.toRawPtr
import scala.scalanative.unsafe.*

/** Global registry for WebSocket connection state, used by static C callbacks.
  *
  * Scala Native CFuncPtr callbacks cannot close over local state. Instead, we store the channel references in this global map indexed by a
  * unique ID. The ID is passed as the `userData` pointer to the C layer, and the static callbacks look up the state by ID.
  */
private[kyo] object WsRegistry:

    case class WsState(
        inbound: Channel.Unsafe[WebSocketFrame],
        outbound: Channel.Unsafe[WebSocketFrame],
        closeRef: AtomicRef.Unsafe[Maybe[(Int, String)]],
        frame: Frame
    )

    private val nextId   = new AtomicLong(1)
    private val registry = new ConcurrentHashMap[Long, WsState]()

    def register(
        inbound: Channel.Unsafe[WebSocketFrame],
        outbound: Channel.Unsafe[WebSocketFrame],
        closeRef: AtomicRef.Unsafe[Maybe[(Int, String)]]
    )(using f: Frame): Long =
        val id = nextId.getAndIncrement()
        registry.put(id, WsState(inbound, outbound, closeRef, f))
        id
    end register

    def unregister(id: Long): Unit =
        discard(registry.remove(id))

    def get(id: Long): WsState =
        registry.get(id)

    private def idFromPtr(ptr: Ptr[Byte]): Long =
        scala.scalanative.runtime.Intrinsics.castRawPtrToLong(toRawPtr(ptr))

    // Static C callback for incoming WebSocket messages
    val msgCallback: H2oBindings.WsMsgFn =
        CFuncPtr4.fromScalaFunction { (userData: Ptr[Byte], opcode: CInt, data: Ptr[Byte], len: CInt) =>
            import AllowUnsafe.embrace.danger
            val state = get(idFromPtr(userData))
            if state != null then
                given Frame = state.frame
                if opcode == 1 then
                    val bytes = new Array[Byte](len)
                    var i     = 0
                    while i < len do
                        bytes(i) = data(i)
                        i += 1
                    discard(state.inbound.offer(WebSocketFrame.Text(new String(bytes, "UTF-8"))))
                else if opcode == 2 then
                    val bytes = new Array[Byte](len)
                    var i     = 0
                    while i < len do
                        bytes(i) = data(i)
                        i += 1
                    discard(state.inbound.offer(WebSocketFrame.Binary(Span.fromUnsafe(bytes))))
                else if opcode == 8 then
                    discard(state.inbound.close())
                    discard(state.outbound.close())
                end if
            end if
        }

    // Static C callback for connection close
    val closeCallback: H2oBindings.WsCloseFn =
        CFuncPtr1.fromScalaFunction { (userData: Ptr[Byte]) =>
            import AllowUnsafe.embrace.danger
            val state = get(idFromPtr(userData))
            if state != null then
                given Frame = state.frame
                discard(state.inbound.close())
                discard(state.outbound.close())
            end if
        }

end WsRegistry
