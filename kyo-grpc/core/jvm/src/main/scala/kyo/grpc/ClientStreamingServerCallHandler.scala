package kyo.grpc

import io.grpc.*
import kyo.*
import kyo.grpc.Grpc

private[grpc] class ClientStreamingServerCallHandler[Request, Response](f: GrpcHandlerInit[Stream[Request, Grpc], Response])(using Frame, Tag[Emit[Chunk[Request]]]) extends BaseClientStreamingServerCallHandler[Request, Response, GrpcHandler[Stream[Request, Grpc], Response]](f):

    override def sent(call: ServerCall[Request, Response], handler: GrpcHandler[Stream[Request, Grpc], Response], channel: StreamChannel[Request, GrpcFailure]): Status < (Grpc & Emit[Metadata]) =
        def onChunk(chunk: Chunk[Request]) =
            Sync.defer(call.request(chunk.size))

        for
            response <- handler(channel.stream.tapChunk(onChunk))
            _ <- Sync.defer(call.sendMessage(response))
        yield Status.OK
    end sent

end ClientStreamingServerCallHandler
