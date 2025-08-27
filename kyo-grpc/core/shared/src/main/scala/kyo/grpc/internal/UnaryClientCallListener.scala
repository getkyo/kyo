package kyo.grpc.internal

import io.grpc.*
import io.grpc.ClientCall.Listener
import kyo.*
import kyo.Channel
import kyo.grpc.RequestEnd

private[grpc] class UnaryClientCallListener[Response](
    val headersPromise: Promise[Metadata, Any],
    val responsePromise: Promise[Response, Abort[StatusException]],
    val completionPromise: Promise[RequestEnd, Any],
    val readySignal: SignalRef[Boolean]
) extends Listener[Response]:

    import AllowUnsafe.embrace.danger

    override def onHeaders(headers: Metadata): Unit =
        headersPromise.unsafe.completeDiscard(Result.succeed(headers))

    override def onMessage(message: Response): Unit =
        if !responsePromise.unsafe.complete(Result.succeed(message)) then
            throw Status.INVALID_ARGUMENT.withDescription("Server sent more than one response.").asException()
    end onMessage

    override def onClose(status: Status, trailers: Metadata): Unit =
        responsePromise.unsafe.completeDiscard(Result.fail(status.asException(trailers)))
        completionPromise.unsafe.completeDiscard(Result.succeed(RequestEnd(status, trailers)))

    override def onReady(): Unit =
        // May not be called if the method type is unary, but it will be called for client streaming.
        readySignal.unsafe.set(true)

end UnaryClientCallListener
