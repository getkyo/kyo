package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.grpc.*
import kyo.grpc.Grpc

abstract private[grpc] class BaseUnaryServerCallHandler[Request, Response, Handler](f: Handler < GrpcResponseMeta)(using Frame)
    extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    protected def send(
        call: ServerCall[Request, Response],
        handler: Handler,
        promise: Promise[Request, Abort[Status]],
        ready: SignalRef[Boolean]
    ): Status < (Grpc & Emit[SafeMetadata])

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] =
        // WARNING: call is not guaranteed to be thread-safe.
        // WARNING: headers are definitely not thread-safe.
        // This handler has ownership of the call and headers, so we can use them with care.

        def sendAndClose(handler: Handler, promise: Promise[Request, Abort[Status]], ready: SignalRef[Boolean]) =
            for
                (trailers, status) <-
                    send(call, handler, promise, ready).handle(
                        Abort.recoverError(ServerCallHandlers.errorStatus),
                        Emit.runFold[SafeMetadata](SafeMetadata.empty)(_.merge(_))
                    )
                _ <- Sync.defer(call.close(status, trailers.toJava))
            yield ()

        def start(handler: Handler, promise: Promise[Request, Abort[Status]], ready: SignalRef[Boolean]) =
            for
                fiber <- Fiber.initUnscoped(sendAndClose(handler, promise, ready))
                _ <- fiber.onInterrupt: _ =>
                    val status = Status.CANCELLED.withDescription("Call was cancelled.")
                    try {
                        call.close(status, SafeMetadata.empty.toJava)
                    } catch {
                        case _: IllegalStateException => // Ignore
                    }
            yield fiber

        val init =
            for
                _ <- Sync.defer(call.request(1))
                (options, handler) <- f.handle(
                    Env.run(SafeMetadata.fromJava(headers)),
                    ResponseOptions.run
                )
                _         <- options.sendHeaders(call)
                ready     <- Signal.initRef(false)
                promise   <- Promise.init[Request, Abort[Status]]
                sentFiber <- start(handler, promise, ready)
            yield UnaryServerCallListener(promise.unsafe, sentFiber.unsafe, ready.unsafe, call)

        init.handle(
            Sync.Unsafe.run,
            Abort.run,
            _.eval.getOrThrow
        )
    end startCall

    private class UnaryServerCallListener(
        request: Promise.Unsafe[Request, Abort[Status]],
        fiber: Fiber.Unsafe[Any, Nothing],
        ready: SignalRef.Unsafe[Boolean],
        call: ServerCall[Request, Response]
    ) extends ServerCall.Listener[Request]:

        override def onMessage(message: Request): Unit =
            // Unlike io.grpc.stub.ServerCalls.UnaryServerCallHandler.UnaryServerCallListener,
            // this does not attempt to detect if the client is misbehaving by sending multiple messages.
            // It is unnecessary as the server can reply and close the call after the first message.
            // It should be up to the client implementation to detect that if it wants to.
            request.completeDiscard(Result.succeed(message))

        override def onHalfClose(): Unit =
            // If the promise has not been completed yet, we complete it with an error, otherwise this does nothing.
            request.completeDiscard(Result.fail(
                Status.INVALID_ARGUMENT.withDescription("Client completed before sending a request.")
            ))

        override def onCancel(): Unit =
            discard(fiber.interrupt())

        override def onComplete(): Unit = ()

        override def onReady(): Unit =
            ready.set(call.isReady)

    end UnaryServerCallListener

end BaseUnaryServerCallHandler
