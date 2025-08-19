package kyo.grpc.internal

import io.grpc.*
import io.grpc.ClientCall.Listener
import kyo.*
import kyo.Channel

private[grpc] class UnaryClientCallListener[Response](
    val headersPromise: Promise[Metadata, Any],
    val responsePromise: Promise[Response, Abort[Status]],
    val completionPromise: Promise[(Status, Metadata), Any],
    val readySignal: SignalRef[Boolean]
) extends Listener[Response]:

    import AllowUnsafe.embrace.danger

    override def onHeaders(headers: Metadata): Unit =
        headersPromise.unsafe.completeDiscard(Result.succeed(headers))

    override def onMessage(message: Response): Unit =
        responsePromise.unsafe.completeDiscard(Result.succeed(message))
        responsePromise.unsafe.completeDiscard(Result.fail(
            Status.INVALID_ARGUMENT.withDescription("Server sent more than one response.")
        ))
    end onMessage

    override def onClose(status: Status, trailers: Metadata): Unit =
        completionPromise.unsafe.completeDiscard(Result.succeed((status, trailers)))

    override def onReady(): Unit =
        // May not be called if the method type is unary, but it will be called for client streaming.
        readySignal.unsafe.set(true)

end UnaryClientCallListener
