package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.Channel
import kyo.grpc.*
import kyo.grpc.Grpc

private[grpc] class BidiStreamingServerCallHandler[Request, Response](f: GrpcHandlerInit[Stream[Request, Grpc], Stream[Response, Grpc]])(
    using
    Frame,
    Tag[Emit[Chunk[Request]]],
    Tag[Emit[Chunk[Response]]]
) extends BaseStreamingServerCallHandler[Request, Response, GrpcHandler[Stream[Request, Grpc], Stream[Response, Grpc]]](f):

    override protected def send(
        call: ServerCall[Request, Response],
        handler: GrpcHandler[Stream[Request, Grpc], Stream[Response, Grpc]],
        channel: Channel[Request],
        ready: SignalRef[Boolean]
    ): Status < (Grpc & Emit[Metadata]) =
        def onChunk(chunk: Chunk[Request]) =
            Sync.defer(call.request(chunk.size))

        def sendMessages(isFirst: AtomicRef[Boolean])(response: Response): Unit < Async =
            // Send the first message whether the call is ready or not and let it buffer internally as a fast path
            // under the assumption that the client will be ready for at least one response after the initial request.
            isFirst.getAndSet(false).flatMap: first =>
                if first || call.isReady then Sync.defer(call.sendMessage(response))
                else ready.next.andThen(sendMessages(isFirst)(response))

        for
            responses <- handler(channel.streamUntilClosed().tapChunk(onChunk))
            isFirst   <- AtomicRef.init(true)
            _         <- responses.foreach(sendMessages(isFirst))
        yield Status.OK
        end for
    end send

end BidiStreamingServerCallHandler
