package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import scala.language.future

// TODO: This should implement ClientCallStreamObserver.
class BidiRequestStreamObserver[Request: Tag, Response: Tag] private (
    f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse,
    requestChannel: StreamChannel[Request, GrpcRequest.Errors],
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame, AllowUnsafe) extends StreamObserver[Request]:

    private val responses = Stream.embed(f(requestChannel.stream))

    // TODO: Handle the backpressure properly.
    /** Only run this once.
      */
    private val start: Unit < Async =
        StreamNotifier.notifyObserver(responses, responseObserver)

    override def onNext(request: Request): Unit =
        // TODO: Do a better job of backpressuring here.
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.put(request)).getOrThrow

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        // TODO: Do a better job of backpressuring here.
        val error = StreamNotifier.throwableToStatusException(throwable)
        val fail = requestChannel.error(error)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow

    override def onCompleted(): Unit =
        Abort.run(IO.Unsafe.run(requestChannel.closeProducer)).eval.getOrThrow

end BidiRequestStreamObserver

object BidiRequestStreamObserver:

    def init[Request: Tag, Response: Tag](
        f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe): BidiRequestStreamObserver[Request, Response] < IO =
        for
            requestChannel <- StreamChannel.init[Request, GrpcRequest.Errors]
            observer = BidiRequestStreamObserver(f, requestChannel, responseObserver)
            // TODO: This seems a bit sneaky.
            _ <- Async.run(observer.start)
        yield observer
    end init

end BidiRequestStreamObserver
