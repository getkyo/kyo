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
        promise: Promise[Request, Abort[Status]],
        ready: SignalRef[Boolean]
    ): Status < (Grpc & Emit[SafeMetadata]) =
        def sendMessages(isFirst: AtomicRef[Boolean])(response: Response): Unit < Async =
            // Send the first message whether the call is ready or not and let it buffer internally as a fast path
            // under the assumption that the client will be ready for at least one response after the initial request.
            isFirst.getAndSet(false).flatMap: first =>
                if first || call.isReady then Sync.defer(call.sendMessage(response))
                else ready.next.andThen(sendMessages(isFirst)(response))

        Abort.run[Status]:
            for
                request   <- promise.get
                responses <- handler(request)
                isFirst   <- AtomicRef.init(true)
                _         <- responses.foreach(sendMessages(isFirst))
            yield Status.OK
        .map(_.fold(identity, identity, e => throw e))
    end send

end ServerStreamingServerCallHandler
