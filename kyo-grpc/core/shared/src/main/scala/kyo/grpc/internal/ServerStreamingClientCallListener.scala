package kyo.grpc.internal

import io.grpc.*
import io.grpc.ClientCall.Listener
import kyo.*
import kyo.grpc.RequestEnd
import kyo.grpc.StreamChannel

private[grpc] class ServerStreamingClientCallListener[Response](
    val headersPromise: Promise[Metadata, Any],
    val responseChannel: StreamChannel[Response, StatusException],
    val completionPromise: Promise[RequestEnd, Any],
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
        responseChannel.unsafe.closeProducer()
        completionPromise.unsafe.completeDiscard(Result.succeed(RequestEnd(status, trailers)))
    end onClose

    override def onReady(): Unit =
        // May not be called if the method type is server streaming.
        readySignal.unsafe.set(true)

end ServerStreamingClientCallListener
