package kyo.grpc

import kyo.*
import kyo.Emit.Ack
import kyo.Emit.Ack.*

object RequestStream:

    private type ErrorOr[Request] = Either[GrpcRequest.Errors, Request]

    // TODO: Set the capacity to something else that matches how we backpressure.
    // TODO: Double check the access pattern here.
    def channel[Request](using Frame): Channel[ErrorOr[Request]] < IO =
        Channel.init[Either[GrpcRequest.Errors, Request]](capacity = 42, access = Access.SingleProducerSingleConsumer)

    def emitFromChannel[Request: Tag](channel: Channel[ErrorOr[Request]], requestsComplete: AtomicBoolean)(ack: Ack)(using
        Frame
    ): Ack < (Emit[Chunk[Request]] & GrpcRequest) =
        ack match
            case Stop        => Stop
            case Continue(0) => Emit.andMap(Chunk.empty)(emitFromChannel(channel, requestsComplete))
            // TODO: Can we take multiple? https://github.com/getkyo/kyo/issues/678
            case Continue(_) =>
                for
                    drained  <- channel.empty
                    complete <- requestsComplete.get
                    ack <-
                        if drained && complete then channel.close.map(_ => (Stop: Ack < Any))
                        else
                            channel.take.map {
                                case Left(t)        => Abort.fail(ResponseStream.throwableToStatusException(t))
                                case Right(request) => Emit.andMap(Chunk(request))(emitFromChannel(channel, requestsComplete))
                            }
                yield ack

end RequestStream
