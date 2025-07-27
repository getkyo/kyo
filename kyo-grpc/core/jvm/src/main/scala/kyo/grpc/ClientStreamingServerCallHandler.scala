package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.Access.MultiProducerSingleConsumer
import kyo.grpc.Grpc

// TODO: Do we ever emit an error now?
//  If not then we should remove the `GrpcFailure` type from the stream.
private[grpc] class ClientStreamingServerCallHandler[Request, Response](f: Stream[Request, Grpc] => Response < Grpc)(using Frame, Tag[Emit[Chunk[Request]]]) extends ServerCallHandler[Request, Response]:

    import AllowUnsafe.embrace.danger

    override def startCall(call: ServerCall[Request, Response], headers: Metadata): ServerCall.Listener[Request] = {
        // WARNING: call is not guaranteed to be thread-safe.

        call.request(1)

        // TODO: Capacity
        // TODO: Cleanup
        val channel: StreamChannel.Unsafe[Request, GrpcFailure] =
            StreamChannel.Unsafe.init(capacity = 42)

        val sent =
            Env.run(headers):
                for
                    response <- f(channel.safe.stream)
                    _ <- Var.use[ServerCallOptions](_.sendHeaders(call))
                    // This might throw an exception if the call is already closed which is OK.
                    // If it is closed then it is because it was interrupted in which case we lost the race.
                    _ <- Sync.defer(call.sendMessage(response))
                yield Status.OK

        val closed =
            Var.isolate.update.use:
                Var.run(ServerCallOptions()):
                    for
                        status <- Abort.recoverError(ServerCallHandlers.errorStatus)(sent)
                        trailers <- Var.get[ServerCallOptions].map(_.trailers)
                        _ <- Sync.defer(call.close(status, trailers))
                    yield ()

        val sentFiber =
            Fiber.initUnscoped(closed)
                .handle(
                    Env.run(headers),
                    Sync.Unsafe.evalOrThrow,
                )

        sentFiber.unsafe.onInterrupt: _ =>
            val status = Status.CANCELLED.withDescription("Call was cancelled.")
            call.close(status, Metadata())

        sentFiber.unsafe.onComplete: _ =>
            channel.close()

        ClientStreamingServerCall(channel, sentFiber.unsafe)
    }

    private class ClientStreamingServerCall(channel: StreamChannel.Unsafe[Request, GrpcFailure], fiber: Fiber.Unsafe[Unit, Any])
        extends ServerCall.Listener[Request]:

        override def onMessage(message: Request): Unit =
            discard(channel.putFiber(message))

        override def onHalfClose(): Unit =
            discard(channel.closeProducerFiber())

        override def onCancel(): Unit =
            discard(fiber.interrupt())

        override def onComplete(): Unit = ()

        override def onReady(): Unit = ()

    end ClientStreamingServerCall

end ClientStreamingServerCallHandler
