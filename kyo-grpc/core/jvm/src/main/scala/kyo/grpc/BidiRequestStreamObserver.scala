package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import scala.language.future

class BidiRequestStreamObserver[Request, Response] private (
    f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse,
    requestChannel: StreamChannel[Request, GrpcRequest.Errors],
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]], Tag[Emit[Chunk[Response]]]) extends StreamObserver[Request]:

    private val responses = Stream.embed(f(requestChannel.stream))

    /** Only run this once.
      */
    private val start: Unit < Async =
        StreamNotifier.notifyObserver(responses, responseObserver)

    override def onNext(request: Request): Unit =
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(requestChannel.put(request)).getOrThrow

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val error = StreamNotifier.throwableToStatusException(throwable)
        val fail = requestChannel.error(error)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow

    override def onCompleted(): Unit =
        val _ = IO.Unsafe.evalOrThrow(Async.run(requestChannel.closeProducer))

end BidiRequestStreamObserver

object BidiRequestStreamObserver:

    def init[Request: Tag, Response](
        f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame, AllowUnsafe, Tag[Emit[Chunk[Request]]], Tag[Emit[Chunk[Response]]]): BidiRequestStreamObserver[Request, Response] < IO =
        for
            requestChannel <- StreamChannel.init[Request, GrpcRequest.Errors]
            observer = BidiRequestStreamObserver(f, requestChannel, responseObserver)
            _ <- Async.run(observer.start)
        yield observer
    end init

end BidiRequestStreamObserver
