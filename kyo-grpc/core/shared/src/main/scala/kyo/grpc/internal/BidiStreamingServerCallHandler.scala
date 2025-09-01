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
        channel: Channel[Request]
    ): Status < (Grpc & Emit[Metadata]) =
        def onChunk(chunk: Chunk[Request]) =
            Sync.defer(call.request(chunk.size))

        for
            responses <- handler(channel.streamUntilClosed().tapChunk(onChunk))
            _         <- responses.foreach(response => Sync.defer(call.sendMessage(response)))
        yield Status.OK
        end for
    end send

end BidiStreamingServerCallHandler
