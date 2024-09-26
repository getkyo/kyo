package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Emit.Ack
import kyo.Emit.Ack.*
import kyo.scheduler.top.Status
import scala.language.future

class BidiRequestStreamObserver[Request: Tag, Response: Flat: Tag] private (
    f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse],
    requestChannel: Channel[Either[GrpcRequest.Exceptions, Request]],
    requestsComplete: AtomicBoolean,
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame) extends StreamObserver[Request]:

    private val responses = f(Stream(Emit.andMap(Chunk.empty[Request])(RequestStream.emitFromChannel(requestChannel, requestsComplete))))

    // TODO: Handle the backpressure properly.
    /** Only run this once.
      */
    private val start: Unit < Async =
        ResponseStream.processMultipleResponses(responseObserver, responses)

    override def onNext(request: Request): Unit =
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(requestChannel.put(Right(request)))).unit.eval

    override def onError(t: Throwable): Unit =
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(requestChannel.put(Left(ResponseStream.throwableToStatusException(t))))).unit.eval

    override def onCompleted(): Unit =
        IO.run(requestsComplete.set(true)).unit.eval

end BidiRequestStreamObserver

object BidiRequestStreamObserver:

    // Need this because we are not allowed to use private constructors in inline methods apparently.
    private def apply[Request: Tag, Response: Flat: Tag](
        f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse],
        requestChannel: Channel[Either[GrpcRequest.Exceptions, Request]],
        requestsComplete: AtomicBoolean,
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame) =
        new BidiRequestStreamObserver(f, requestChannel, requestsComplete, responseObserver)

    inline def init[Request: Tag, Response: Flat: Tag](
        f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse],
        responseObserver: ServerCallStreamObserver[Response]
    )(using Frame): BidiRequestStreamObserver[Request, Response] < IO =
        for
            requestChannel   <- RequestStream.channel[Request]
            requestsComplete <- AtomicBoolean.init(false)
            observer = apply(f, requestChannel, requestsComplete, responseObserver)
            // TODO: This seems a bit sneaky.
            _ <- Async.run(observer.start)
        yield observer
    end init

end BidiRequestStreamObserver
