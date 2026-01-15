package kyo.grpc.internal

import io.grpc.ClientCall.Listener
import io.grpc.{Metadata, Status}
import kyo.*
import kyo.grpc.CallClosed

private[grpc] class ServerStreamingClientCallListener[Response](
    val headersPromise: Promise[Metadata, Any],
    val responseChannel: Channel[Response],
    val completionPromise: Promise[CallClosed, Any],
    val readySignal: SignalRef[Boolean]
) extends Listener[Response]:

    import AllowUnsafe.embrace.danger

    override def onHeaders(headers: Metadata): Unit =
        headersPromise.unsafe.completeDiscard(Result.succeed(headers))

    override def onMessage(message: Response): Unit =
        given Frame = Frame.internal
        discard(responseChannel.unsafe.offer(message).getOrThrow)

    override def onClose(status: Status, trailers: Metadata): Unit =
        given Frame = Frame.internal
        discard(responseChannel.unsafe.closeAwaitEmpty())
        completionPromise.unsafe.completeDiscard(Result.succeed(CallClosed(status, trailers)))
    end onClose

    override def onReady(): Unit =
        // May not be called if the method type is server streaming.
        readySignal.unsafe.set(true)

end ServerStreamingClientCallListener
