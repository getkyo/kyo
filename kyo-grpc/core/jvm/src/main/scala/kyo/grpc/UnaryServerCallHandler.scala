package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

private[grpc] class UnaryServerCallHandler[Request, Response](f: GrpcHandlerInit[Request, Response])(using Frame)
    extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] =
        // WARNING: call is not guaranteed to be thread-safe.
        // WARNING: headers are definitely not thread-safe.
        // This handler has ownership of the call and headers, so we can use them with care.

        def sent(handler: GrpcHandler[Request, Response], promise: Promise[Request, Abort[Status]]) =
            for
                request  <- promise.get
                response <- handler(request)
                _        <- Sync.defer(call.sendMessage(response))
            yield Status.OK

        def closed(handler: GrpcHandler[Request, Response], promise: Promise[Request, Abort[Status]]) =
            for
                (trailers, status) <-
                    sent(handler, promise).handle(
                        Abort.recoverError(ServerCallHandlers.errorStatus),
                        Emit.runFold[Metadata](Metadata())(_.mergeSafe(_))
                    )
                _ <- Sync.defer(call.close(status, trailers))
            yield ()

        def start(handler: GrpcHandler[Request, Response], promise: Promise[Request, Abort[Status]]) =
            for
                fiber <- Fiber.initUnscoped(closed(handler, promise))
                _ <- fiber.onInterrupt: _ =>
                    val status = Status.CANCELLED.withDescription("Call was cancelled.")
                    call.close(status, Metadata())
            yield fiber

        val init =
            for
                _ <- Sync.defer(call.request(1))
                (options, handler) <- f.handle(
                    Env.run(headers),
                    ResponseOptions.run
                )
                _         <- options.sendHeaders(call)
                promise   <- Promise.init[Request, Abort[Status]]
                sentFiber <- start(handler, promise)
            yield UnaryServerCallListener(promise.unsafe, sentFiber.unsafe)

        init.handle(
            Sync.Unsafe.run,
            Abort.run,
            _.eval.getOrThrow
        )
    end startCall

    class UnaryServerCallListener(promise: Promise.Unsafe[Request, Abort[Status]], fiber: Fiber.Unsafe[Any, Nothing])
        extends ServerCall.Listener[Request]:

        override def onMessage(message: Request): Unit =
            if !promise.complete(Result.succeed(message)) then
                throw new StatusException(
                    Status.INVALID_ARGUMENT.withDescription("Client sent more than one request."),
                    Metadata()
                )

        override def onHalfClose(): Unit =
            // If the promise has not been completed yet, we complete it with an error.
            promise.completeDiscard(Result.fail(
                Status.INVALID_ARGUMENT.withDescription("Client completed before sending a request.")
            ))

        override def onCancel(): Unit =
            discard(fiber.interrupt())

        override def onComplete(): Unit = ()

        override def onReady(): Unit = ()

    end UnaryServerCallListener

end UnaryServerCallHandler
