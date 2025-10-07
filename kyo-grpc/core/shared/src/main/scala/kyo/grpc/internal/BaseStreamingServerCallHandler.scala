package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.Channel
import kyo.grpc.*
import kyo.grpc.Grpc

abstract private[grpc] class BaseStreamingServerCallHandler[Request, Response, Handler](f: Handler < GrpcResponseMeta)(using Frame)
    extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    protected def send(
        call: ServerCall[Request, Response],
        handler: Handler,
        channel: Channel[Request]
    ): Status < (Grpc & Emit[Metadata])

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] =
        // WARNING: call is not guaranteed to be thread-safe.
        // WARNING: headers are definitely not thread-safe.
        // This handler has ownership of the call and headers, so we can use them with care.

        def sendAndClose(handler: Handler, channel: Channel[Request]) =
            for
                (trailers, status) <-
                    send(call, handler, channel).handle(
                        Abort.recoverError(ServerCallHandlers.errorStatus),
                        Emit.runFold[Metadata](Metadata())(_.mergeSafe(_))
                    )
                _ <- Sync.defer(call.close(status, trailers))
            yield ()

        def start(handler: Handler, channel: Channel[Request]) =
            for
                fiber <- Fiber.initUnscoped(sendAndClose(handler, channel))
                _ <- fiber.onInterrupt: _ =>
                    val status = Status.CANCELLED.withDescription("Call was cancelled.")
                    try {
                        call.close(status, Metadata())
                    } catch {
                        case _: IllegalStateException => // Ignore
                    }
                _ <- fiber.onComplete: _ =>
                    channel.close
            yield fiber

        val init =
            for
                // Request 1 up front to ensure that we get the headers.
                _ <- Sync.defer(call.request(1))
                (options, handler) <- f.handle(
                    Env.run(headers),
                    ResponseOptions.run
                )
                requestBuffer = options.requestBufferOrDefault
                _ <- options.sendHeaders(call)
                // Request the remaining messages to fill the request buffer.
                _         <- Sync.defer(if requestBuffer > 1 then call.request(requestBuffer - 1) else ())
                channel   <- Channel.initUnscoped[Request](capacity = requestBuffer, access = Access.SingleProducerSingleConsumer)
                sentFiber <- start(handler, channel)
            yield StreamingServerCallListener(channel.unsafe, sentFiber.unsafe)

        init.handle(
            Sync.Unsafe.run,
            Abort.run,
            _.eval.getOrThrow
        )
    end startCall

    private class StreamingServerCallListener(requests: Channel.Unsafe[Request], fiber: Fiber.Unsafe[Any, Nothing])
        extends ServerCall.Listener[Request]:

        override def onMessage(message: Request): Unit =
            discard(requests.putFiber(message))

        override def onHalfClose(): Unit =
            discard(requests.closeAwaitEmpty())

        override def onCancel(): Unit =
            discard(fiber.interrupt())

        override def onComplete(): Unit = ()

        // FIXME: Use a Signal here.
        override def onReady(): Unit = ()

    end StreamingServerCallListener

end BaseStreamingServerCallHandler
