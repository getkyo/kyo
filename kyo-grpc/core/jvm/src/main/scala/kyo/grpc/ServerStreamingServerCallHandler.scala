package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

private[grpc] class ServerStreamingServerCallHandler[Request, Response](f: Request => Stream[Response, Grpc] < Grpc)(using Frame, Tag[Emit[Chunk[Response]]]) extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        // WARNING: call is not guaranteed to be thread-safe.
        call.request(1)
        ServerStreamingServerCallListener(call, headers)
    }

    private class ServerStreamingServerCallListener(call: ServerCall[Request, Response], headers: Metadata)
        extends ServerCall.Listener[Request]:

        private val interrupt: Promise[Status, Any] =
            Promise.Unsafe.initMasked[Status, Any]().safe

        private val messageReceived: AtomicBoolean =
            AtomicBoolean.Unsafe.init(false).safe

        private val pendingSend: AtomicRef[Maybe[Latch]] =
            AtomicRef.Unsafe.init(Maybe.empty).safe

        override def onMessage(message: Request): Unit =
            val sent =
                Env.run(headers):
                    for
                        responses <- f(message)
                        _ <- Var.use[ServerCallOptions](_.sendHeaders(call))
                        _ <- responses.foreach(sendResponse)
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

        private def sendResponse(response: Response) =
            // sendMessage might throw an exception if the call is already closed which is OK.
            // If it is closed then it is because it was interrupted in which case we lost the race.
            val send = Sync.defer(call.sendMessage(response))
            if call.isReady then
                send
            else
                for
                    latch <- Latch.init(1)
                    _ <- pendingSend.set(Maybe.Present(latch))
                    _ <-
                        if call.isReady then
                            pendingSend.set(Maybe.Absent).andThen(send)
                        else
                            latch.await.andThen(send)
                yield ()
            end if
        end sendResponse

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

        override def onReady(): Unit =
            pendingSend.unsafe.get().foreach(_.unsafe.release())

    end ServerStreamingServerCallListener

end ServerStreamingServerCallHandler
