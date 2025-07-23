package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

class UnaryServerCallHandler[Request, Response](f: Request => Response < Grpc) extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        call.request(1)
        UnaryServerCallListener(f)(call, headers)
    }

    class UnaryServerCallListener(f: Request => Response < GrpcHandler)(call: ServerCall[Request, Response], headers: Metadata)
        extends ServerCall.Listener[Request]:

        private val interrupt: Promise[Nothing, Any] = Promise.Unsafe.initMasked[Nothing, Any]().safe

        private val messageReceived: AtomicBoolean = AtomicBoolean.Unsafe.init(false).safe

        override def onMessage(message: Request): Unit =
            // TODO: What frame to use here?
            given Frame = Frame.internal

            val sent =
                for
                    response <- Env.run(headers)(f(message))
                    _ <- Var.use[ServerCallOptions](_.sendHeaders(call))
                    // This might throw an exception if the call is already closed which is OK.
                    // If it is closed then it is because it was interrupted in which case we lost the race.
                    _ <- Sync.defer(call.sendMessage(response))
                yield Status.OK

            val cancelled =
                interrupt.get
                    .andThen(Status.CANCELLED.withDescription("Call was cancelled."))

            val closed =
                Var.isolate.update.use:
                    Var.run(ServerCallOptions()):
                        for
                            status <- Abort.recoverError(errorStatus):
                                Async.raceFirst(sent, cancelled)
                            trailers <- Var.get[ServerCallOptions].map(_.trailers)
                            _ <- Sync.defer(call.close(status, trailers))
                        yield ()

            val closedOrSkipped =
                Kyo.unless(messageReceived.getAndSet(true))(closed).unit

            KyoApp.Unsafe.runAndBlock(Duration.Infinity)(closedOrSkipped).getOrThrow
        end onMessage

        override def onHalfClose(): Unit = ()

        override def onCancel(): Unit =
            interrupt.unsafe.completeDiscard(Result.panic(Interrupted(Frame.internal, "Unary call cancelled")))

        override def onComplete(): Unit = ()

        override def onReady(): Unit = ()

    end UnaryServerCallListener

    private def errorStatus(error: Result.Error[Throwable])(using Frame): Status < Var[ServerCallOptions] =
        val t = error.failureOrPanic
        val status = Status.fromThrowable(t)
        Maybe(Status.trailersFromThrowable(t)) match
            case Maybe.Absent => status
            case Maybe.Present(trailers) =>
                Var.update[ServerCallOptions](_.mergeTrailers(trailers))
                    .andThen(status)

end UnaryServerCallHandler
