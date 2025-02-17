package kyo.grpc

import io.grpc.Grpc
import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import kyo.scheduler.top.Status
import scala.language.future

class BidiRequestStreamObserver[Request: Tag, Response: { Flat, Tag }] private (
    f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse,
    requestChannel: Channel[Result[GrpcRequest.Errors, Request]],
    requestsCompleted: AtomicBoolean,
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame, AllowUnsafe) extends StreamObserver[Request]:

    private val responses = Stream.embed(f(StreamChannel.stream(requestChannel, requestsCompleted)))

    // TODO: Handle the backpressure properly.
    /** Only run this once.
      */
    private val start: Unit < Async =
        StreamNotifier.notifyObserver(responses, responseObserver)

    override def onNext(request: Request): Unit =
        // TODO: Do a better job of backpressuring here.
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.put(Success(request))).getOrThrow

    override def onError(t: Throwable): Unit =
        // TODO: Do a better job of backpressuring here.
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.put(Failure(StreamNotifier.throwableToStatusException(t)))).getOrThrow

    override def onCompleted(): Unit =
        Abort.run(IO.Unsafe.run(requestsCompleted.set(true))).eval.getOrThrow

end BidiRequestStreamObserver

object BidiRequestStreamObserver:

    def init[Request: Tag, Response: { Flat, Tag }](
        f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe): BidiRequestStreamObserver[Request, Response] < IO =
        for
            requestChannel    <- StreamChannel.init[Request, GrpcRequest.Errors]
            requestsCompleted <- AtomicBoolean.init(false)
            observer = BidiRequestStreamObserver(f, requestChannel, requestsCompleted, responseObserver)
            // TODO: This seems a bit sneaky.
            _ <- Async.run(observer.start)
        yield observer
    end init

end BidiRequestStreamObserver
