package kyo.grpc.internal

import io.grpc.*
import io.grpc.ClientCall.Listener
import kyo.*
import kyo.Channel
import kyo.grpc.CallClosed
import kyo.grpc.SafeMetadata

private[grpc] class ServerStreamingClientCallListener[Response](
    val headersPromise: Promise[SafeMetadata, Any],
    val responseChannel: Channel[Response],
    val completionPromise: Promise[CallClosed, Any],
    val readySignal: SignalRef[Boolean]
) extends Listener[Response]:

    import AllowUnsafe.embrace.danger
    private given Frame = Frame.internal

    override def onHeaders(headers: Metadata): Unit =
        headersPromise.unsafe.completeDiscard(Result.succeed(SafeMetadata.fromJava(headers)))

    override def onMessage(message: Response): Unit =
        val _ = responseChannel.unsafe.offer(message)

    override def onClose(status: Status, trailers: Metadata): Unit =
        val _ = responseChannel.unsafe.close()
        completionPromise.unsafe.completeDiscard(Result.succeed(CallClosed(status, SafeMetadata.fromJava(trailers))))

    override def onReady(): Unit =
        // May not be called if the method type is unary.
        readySignal.unsafe.set(true)

end ServerStreamingClientCallListener
