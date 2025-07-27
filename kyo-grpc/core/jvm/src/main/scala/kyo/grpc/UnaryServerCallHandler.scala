package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

private[grpc] class UnaryServerCallHandler[Request, Response](f: Request => Response < Grpc)(using Frame) extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        // WARNING: call is not guaranteed to be thread-safe.
        call.request(1)
        UnaryServerCallListener(call, headers)
    }

    private class UnaryServerCallListener(call: ServerCall[Request, Response], headers: Metadata)
        extends ServerCall.Listener[Request]:

        private val interrupt: Promise[Status, Any] =
            Promise.Unsafe.initMasked[Status, Any]().safe

        private val messageReceived: AtomicBoolean =
            AtomicBoolean.Unsafe.init(false).safe

        override def onMessage(message: Request): Unit =
            val sent =
                Env.run(headers):
                    for
                        response <- f(message)
                        _ <- Var.use[ServerCallOptions](_.sendHeaders(call))
                        // sendMessage might throw an exception if the call is already closed which is OK.
                        // If it is closed then it is because it was interrupted in which case we lost the race.
                        _ <- Sync.defer(call.sendMessage(response))
                    yield Status.OK

            val closed =
                Var.isolate.update.use:
                    Var.run(ServerCallOptions()):
                        for
                            status <- Abort.recoverError(ServerCallHandlers.errorStatus):
                                Async.raceFirst(sent, interrupt.get)
                            trailers <- Var.get[ServerCallOptions].map(_.trailers)
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
        end onCancel

        override def onComplete(): Unit = ()

        override def onReady(): Unit = ()

    end UnaryServerCallListener

end UnaryServerCallHandler
