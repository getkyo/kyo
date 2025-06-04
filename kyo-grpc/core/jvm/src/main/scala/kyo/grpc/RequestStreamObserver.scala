package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*

/**
 * This is not thread-safe.
 */
class RequestStreamObserver[Request, Response](
    f: Stream[Request, GrpcRequest] => Response < GrpcResponse,
    requestChannel: StreamChannel[Request, GrpcRequest.Errors],
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]]) extends StreamObserver[Request]:

    private val response = f(requestChannel.stream)

    /** Only run this once.
      */
    private val start: Unit < Async =
        StreamNotifier.notifyObserver(response, responseObserver)

    override def onNext(request: Request): Unit =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.put(request)).getOrThrow

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val error = StreamNotifier.throwableToStatusException(throwable)
        val fail = requestChannel.error(error)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow

    override def onCompleted(): Unit =
        val _ = IO.Unsafe.evalOrThrow(Async.run(requestChannel.closeProducer))

end RequestStreamObserver

object RequestStreamObserver:

    def init[Request: Tag, Response](
        f: Stream[Request, GrpcRequest] => Response < GrpcResponse,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]]): RequestStreamObserver[Request, Response] < IO =
        for
            requestChannel <- StreamChannel.init[Request, GrpcRequest.Errors]
            observer = RequestStreamObserver(f, requestChannel, responseObserver)
            _ <- Async.run(observer.start)
        yield observer
    end init

end RequestStreamObserver
