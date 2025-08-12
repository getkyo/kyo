package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

// TODO: Do we ever emit an error now?
//  If not then we should remove the `GrpcFailure` type from the stream.
private[grpc] class BidiStreamingServerCallHandler[Request, Response](f: GrpcHandlerInit[Stream[Request, Grpc], Stream[Response, Grpc]])(
    using
    Frame,
    Tag[Emit[Chunk[Request]]],
    Tag[Emit[Chunk[Response]]]
) extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] =
        // WARNING: call is not guaranteed to be thread-safe.
        // WARNING: headers are definitely not thread-safe.
        // This handler has ownership of the call and headers, so we can use them with care.

        def onChunk(chunk: Chunk[Request]) =
            Sync.defer(call.request(chunk.size))

        def sent(handler: GrpcHandler[Stream[Request, Grpc], Stream[Response, Grpc]], channel: StreamChannel[Request, GrpcFailure]) =
            for
                responses <- handler(channel.stream.tapChunk(onChunk))
                _         <- responses.foreach(response => Sync.defer(call.sendMessage(response)))
            yield Status.OK

        def closed(handler: GrpcHandler[Stream[Request, Grpc], Stream[Response, Grpc]], channel: StreamChannel[Request, GrpcFailure]) =
            for
                (trailers, status) <-
                    sent(handler, channel).handle(
                        Abort.recoverError(ServerCallHandlers.errorStatus),
                        Emit.runFold[Metadata](Metadata())(_.mergeSafe(_))
                    )
                _ <- Sync.defer(call.close(status, trailers))
            yield ()

        def start(handler: GrpcHandler[Stream[Request, Grpc], Stream[Response, Grpc]], channel: StreamChannel[Request, GrpcFailure]) =
            for
                fiber <- Fiber.initUnscoped(closed(handler, channel))
                _ <- fiber.onInterrupt: _ =>
                    val status = Status.CANCELLED.withDescription("Call was cancelled.")
                    call.close(status, Metadata())
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
                channel   <- StreamChannel.initUnscoped[Request, GrpcFailure](capacity = requestBuffer)
                sentFiber <- start(handler, channel)
            yield ClientStreamingServerCallListener(channel.unsafe, sentFiber.unsafe)

        init.handle(
            Sync.Unsafe.run,
            Abort.run,
            _.eval.getOrThrow
        )
    end startCall

    class ClientStreamingServerCallListener(channel: StreamChannel.Unsafe[Request, ?], fiber: Fiber.Unsafe[Any, Nothing])
        extends ServerCall.Listener[Request]:

        override def onMessage(message: Request): Unit =
            discard(channel.putFiber(message))

        override def onHalfClose(): Unit =
            discard(channel.closeProducerFiber())

        override def onCancel(): Unit =
            discard(fiber.interrupt())

        override def onComplete(): Unit = ()

        override def onReady(): Unit = ()

    end ClientStreamingServerCallListener

end BidiStreamingServerCallHandler
