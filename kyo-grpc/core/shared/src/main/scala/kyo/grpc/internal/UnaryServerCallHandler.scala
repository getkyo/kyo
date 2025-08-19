package kyo.grpc.internal

import io.grpc.*
import kyo.*
import kyo.grpc.{Grpc, *}

private[grpc] class UnaryServerCallHandler[Request, Response](f: GrpcHandlerInit[Request, Response])(using Frame)
    extends BaseUnaryServerCallHandler[Request, Response, GrpcHandler[Request, Response]](f):

    override protected def send(call: ServerCall[Request, Response], handler: GrpcHandler[Request, Response], promise: Promise[Request, Abort[Status]]): Status < (Grpc & Emit[Metadata]) =
        Abort.merge:
            for
                request <- promise.get
                response <- handler(request)
                _ <- Sync.defer(call.sendMessage(response))
            yield Status.OK
    end send

end UnaryServerCallHandler
