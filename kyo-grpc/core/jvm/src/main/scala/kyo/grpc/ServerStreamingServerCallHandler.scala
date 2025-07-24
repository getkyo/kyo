package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

private[grpc] class ServerStreamingServerCallHandler[Request, Response](f: Request => Stream[Response, Grpc] < Grpc)(using Tag[Emit[Chunk[Response]]]) extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        call.request(1)
        ServerStreamingServerCallListener(call, headers)
    }

    class ServerStreamingServerCallListener(call: ServerCall[Request, Response], headers: Metadata)
        extends ServerCall.Listener[Request]:

        private val interrupt: Promise[Nothing, Any] = Promise.Unsafe.initMasked[Nothing, Any]().safe

        private val messageReceived: AtomicBoolean = AtomicBoolean.Unsafe.init(false).safe

        override def onMessage(message: Request): Unit =
            // TODO: What frame to use here?
            given Frame = Frame.internal

            val sent =
                Env.run(headers):
                    for
                        responses <- f(message)
                        _ <- Var.use[ServerCallOptions](_.sendHeaders(call))
                        // This might throw an exception if the call is already closed which is OK.
                        // If it is closed then it is because it was interrupted in which case we lost the race.
                        _ <- responses.foreach: response =>
                            Sync.defer(call.sendMessage(response))
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

    end ServerStreamingServerCallListener

    private def errorStatus(error: Result.Error[Throwable])(using Frame): Status < Var[ServerCallOptions] =
        val t = error.failureOrPanic
        val status = Status.fromThrowable(t)
        Maybe(Status.trailersFromThrowable(t)) match
            case Maybe.Absent => status
            case Maybe.Present(trailers) =>
                Var.update[ServerCallOptions](_.mergeTrailers(trailers))
                    .andThen(status)

end ServerStreamingServerCallHandler
