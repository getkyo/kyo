package kyo.grpc

import io.grpc.Grpc
import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*

// TODO: Should this extend ServerCallStreamObserver?

/**
 * This is not thread-safe.
 */
class RequestStreamObserver[Request: Tag, Response: Flat](
    f: Stream[Request, GrpcRequest] => Response < GrpcResponse,
    requestChannel: StreamChannel[Request, GrpcRequest.Errors],
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame, AllowUnsafe) extends StreamObserver[Request]:

    private val response = f(requestChannel.stream)

    // TODO: Handle the backpressure properly.
    // TODO: It should be possible to stop all these observers.
    /** Only run this once.
      */
    private val start: Unit < Async =
        StreamNotifier.notifyObserver(response, responseObserver)

    override def onNext(request: Request): Unit =
        // TODO: Do a better job of backpressuring here.
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.put(request)).getOrThrow

    override def onError(t: Throwable): Unit =
        // TODO: Do a better job of backpressuring here.
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.error(StreamNotifier.throwableToStatusException(t))).getOrThrow

    override def onCompleted(): Unit =
        Abort.run(IO.Unsafe.run(requestChannel.complete)).eval.getOrThrow

end RequestStreamObserver

object RequestStreamObserver:

    def init[Request: Tag, Response: Flat: Tag](
        f: Stream[Request, GrpcRequest] => Response < GrpcResponse,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe): RequestStreamObserver[Request, Response] < IO =
        for
            requestChannel <- StreamChannel.init[Request, GrpcRequest.Errors]
            observer = RequestStreamObserver(f, requestChannel, responseObserver)
            // TODO: This seems a bit sneaky.
            _ <- Async.run(observer.start)
        yield observer
    end init

end RequestStreamObserver
