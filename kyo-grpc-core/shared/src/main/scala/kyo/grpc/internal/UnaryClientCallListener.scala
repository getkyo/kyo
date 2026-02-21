package kyo.grpc.internal

import io.grpc.*
import io.grpc.ClientCall.Listener
import kyo.*
import kyo.Channel
import kyo.grpc.CallClosed
import kyo.grpc.SafeMetadata

private[grpc] class UnaryClientCallListener[Response](
    val headersPromise: Promise[SafeMetadata, Any],
    val responsePromise: Promise[Response, Abort[StatusException]],
    val completionPromise: Promise[CallClosed, Any],
    val readySignal: SignalRef[Boolean]
) extends Listener[Response]:

    import AllowUnsafe.embrace.danger

    override def onHeaders(headers: Metadata): Unit =
        headersPromise.unsafe.completeDiscard(Result.succeed(SafeMetadata.fromJava(headers)))

    override def onMessage(message: Response): Unit =
        if !responsePromise.unsafe.complete(Result.succeed(message)) then
            throw Status.INVALID_ARGUMENT.withDescription("Server sent more than one response.").asException()
    end onMessage

    override def onClose(status: Status, trailers: Metadata): Unit =
        responsePromise.unsafe.completeDiscard(Result.fail(status.asException(trailers)))
        completionPromise.unsafe.completeDiscard(Result.succeed(CallClosed(status, SafeMetadata.fromJava(trailers))))

    override def onReady(): Unit =
        // May not be called if the method type is unary.
        readySignal.unsafe.set(true)

end UnaryClientCallListener
