package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.grpc.*
import kyo.grpc.Grpc

private[grpc] class ServerStreamingServerCallHandler[Request, Response](f: GrpcHandlerInit[Request, Stream[Response, Grpc]])(using
    Frame,
    Tag[Emit[Chunk[Response]]]
) extends BaseUnaryServerCallHandler[Request, Response, GrpcHandler[Request, Stream[Response, Grpc]]](f):

    import AllowUnsafe.embrace.danger

    override protected def send(
        call: ServerCall[Request, Response],
        handler: GrpcHandler[Request, Stream[Response, Grpc]],
        promise: Promise[Request, Abort[Status]]
    ): Status < (Grpc & Emit[Metadata]) =
        Abort.merge:
            for
                request   <- promise.get
                responses <- handler(request)
                _         <- responses.foreach(response => Sync.defer(call.sendMessage(response)))
            yield Status.OK
    end send

end ServerStreamingServerCallHandler
