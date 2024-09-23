package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Emit.Ack
import kyo.Emit.Ack.*
import kyo.scheduler.top.Status
import scala.language.future

class BidiRequestStreamObserver[Request, Response: Flat] private (
    f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse],
    responseObserver: ServerCallStreamObserver[Response]
)(using frame: Frame, requestTag: Tag[Emit[Chunk[Request]]], responseTag: Tag[Emit[Chunk[Response]]]) extends StreamObserver[Request]:

    // TODO: Set the capacity to something else that matches how we backpressure.
    // TODO: Double check the access pattern here.
    private val channel: Channel[Either[GrpcRequest.Exceptions, Request]] =
        IO.run(Channel.init[Either[GrpcRequest.Exceptions, Request]](capacity = 42, access = Access.SingleProducerSingleConsumer)).eval

    @volatile private var closed = false

    private def emitNext(ack: Ack): Ack < (Emit[Chunk[Request]] & GrpcRequest) =
        ack match
            case Stop => Stop
            // TODO: Can we take multiple? https://github.com/getkyo/kyo/issues/678
            case Continue(_) =>
                for
                    drained <- channel.empty
                    ack <-
                        if drained && closed then (Stop: Ack < Any)
                        else
                            channel.take.map {
                                case Left(t)        => Abort.fail(ServerHandler.throwableToStatusException(t))
                                case Right(request) => Emit.andMap(Chunk(request))(emitNext)(using requestTag, frame)
                            }
                yield ack

    private val responses = f(Stream(Emit.andMap(Chunk.empty[Request])(emitNext)))

    // TODO: Handle the backpressure properly.
    /** Only run this once.
      */
    private val start: Unit < IO =
        // TODO: Why do I need to specify all the types here to get it to not think there is a discarded non-unit value?
        Async.run(Abort.run[StatusException].apply[Unit, Async, Any, Nothing](responses.runForeach(responseObserver.onNext)(using
            responseTag,
            frame
        )).map(ServerHandler.processCompleted(responseObserver, _))).unit

    override def onNext(request: Request): Unit =
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(channel.put(Right(request)))).unit.eval

    override def onError(t: Throwable): Unit =
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(channel.put(Left(ServerHandler.throwableToStatusException(t))))).unit.eval

    override def onCompleted(): Unit =
        closed = true

end BidiRequestStreamObserver

object BidiRequestStreamObserver:

    // Need this because we are not allowed to use private constructors in inline methods apparently.
    private def apply[Request, Response: Flat](
        f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse],
        responseObserver: ServerCallStreamObserver[Response]
    )(using frame: Frame, requestTag: Tag[Emit[Chunk[Request]]], responseTag: Tag[Emit[Chunk[Response]]]) =
        new BidiRequestStreamObserver(f, responseObserver)

    inline def init[Request: Tag, Response: Flat: Tag](
        f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse],
        responseObserver: ServerCallStreamObserver[Response]
    )(using
        Frame
    ): BidiRequestStreamObserver[Request, Response] < IO =
        val observer = apply(f, responseObserver)
        observer.start.map(_ => observer)
    end init

end BidiRequestStreamObserver
