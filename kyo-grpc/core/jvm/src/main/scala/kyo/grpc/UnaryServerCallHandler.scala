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

        val init =
            for
                _ <- Sync.defer(call.request(1))
                handler <- f.handle(
                    Env.run(headers),
                    ResponseOptions.runSend(call)
                )
                interrupt       <- Promise.init[Status, Any]
                messageReceived <- AtomicBoolean.init(false)
            yield UnaryServerCallListener(call, handler, interrupt, messageReceived)

        init.handle(
            Sync.Unsafe.run,
            Abort.run,
            _.eval.getOrThrow
        )
    end startCall

    class UnaryServerCallListener(
        call: ServerCall[Request, Response],
        handler: GrpcHandler[Request, Response],
        interrupt: Promise[Status, Any],
        messageReceived: AtomicBoolean
    ) extends ServerCall.Listener[Request]:

        override def onMessage(message: Request): Unit =
            val sent =
                for
                    response <- handler(message)
                    // sendMessage might throw an exception if the call is already closed which is OK.
                    // If it is closed then it is because it was interrupted in which case we lost the race.
                    _ <- Sync.defer(call.sendMessage(response))
                yield Status.OK

            val closed =
                for
                    (trailers, status) <-
                        Emit.isolate.merge.use(
                            Async.raceFirst[GrpcFailure, Status, Emit[Metadata]](sent, interrupt.get)
                        ).handle(
                            Abort.recoverError(ServerCallHandlers.errorStatus),
                            Emit.runFold[Metadata](Metadata())(_.mergeSafe(_))
                        )
                    // TODO: Is it safe to call close here?
                    //  Does interrupt guarantee that other fiber has stopped?
                    _ <- Sync.defer(call.close(status, trailers))
                yield ()

            val closedOrSkipped =
                Kyo.unless(messageReceived.getAndSet(true))(closed).unit

            KyoApp.Unsafe.runAndBlock(Duration.Infinity)(closedOrSkipped).getOrThrow
        end onMessage

        override def onHalfClose(): Unit = ()

        override def onCancel(): Unit =
            val status = Status.CANCELLED.withDescription("Call was cancelled.")
            if messageReceived.unsafe.get() then
                interrupt.unsafe.completeDiscard(Result.succeed(status))
            else
                // It is safe to call close here as the listener is not called concurrently so we know there is no other
                // fiber processing a message.
                call.close(status, Metadata())
            end if
        end onCancel

        override def onComplete(): Unit = ()

        override def onReady(): Unit = ()

    end UnaryServerCallListener

end UnaryServerCallHandler
