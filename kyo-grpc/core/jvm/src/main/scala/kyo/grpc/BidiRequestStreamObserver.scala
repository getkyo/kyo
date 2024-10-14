package kyo.grpc

import io.grpc.Grpc
import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Emit.Ack
import kyo.Emit.Ack.*
import kyo.Result.*
import kyo.scheduler.top.Status
import scala.language.future

class BidiRequestStreamObserver[Request: Tag, Response: Flat: Tag] private (
    f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse,
    requestChannel: Channel[Result[GrpcRequest.Errors, Request]],
    requestsCompleted: AtomicBoolean,
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame) extends StreamObserver[Request]:

    private val responses = Stream.embed(f(StreamChannel.stream(requestChannel, requestsCompleted)))

    // TODO: Handle the backpressure properly.
    /** Only run this once.
      */
    private val start: Unit < Async =
        StreamNotifier.notifyObserver(responses, responseObserver)

    override def onNext(request: Request): Unit =
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(requestChannel.put(Success(request)))).unit.eval

    override def onError(t: Throwable): Unit =
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(requestChannel.put(Fail(StreamNotifier.throwableToStatusException(t))))).unit.eval

    override def onCompleted(): Unit =
        IO.run(requestsCompleted.set(true)).unit.eval

end BidiRequestStreamObserver

object BidiRequestStreamObserver:

    def init[Request: Tag, Response: Flat: Tag](
        f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame): BidiRequestStreamObserver[Request, Response] < IO =
        for
            requestChannel    <- StreamChannel.init[Request, GrpcRequest.Errors]
            requestsCompleted <- AtomicBoolean.init(false)
            observer = BidiRequestStreamObserver(f, requestChannel, requestsCompleted, responseObserver)
            // TODO: This seems a bit sneaky.
            _ <- Async.run(observer.start)
        yield observer
    end init

end BidiRequestStreamObserver
