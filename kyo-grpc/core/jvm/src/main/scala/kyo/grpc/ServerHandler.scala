package kyo.grpc

import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.ServerCalls
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*

// TODO: Rename.
object ServerHandler:

    // TODO: We should probably implement the ServerCallHandler's ourselves like ZIO does.

    def unary[Request, Response: Flat](f: Request => Response < GrpcResponse)(using Frame): ServerCallHandler[Request, Response] =
        ServerCalls.asyncUnaryCall { (request, responseObserver) =>
            val response = f(request)
            val completed =
                processResponse(responseObserver, response)(responseObserver.onNext.andThen(_ => responseObserver.onCompleted()))
            IO.run(Async.run(completed)).unit.eval
        }

    def clientStreaming[Request, Response: Flat](f: Request < GrpcRequest => Option[Response] < GrpcResponse)(using
        Frame
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncClientStreamingCall(responseObserver =>
            new RequestStreamObserver(f, responseObserver.asInstanceOf[ServerCallStreamObserver[Response]])
        )

    def serverStreaming[Request: Tag, Response: Flat: Tag](f: Request => Stream[Response, GrpcResponse])(using
        Frame
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncServerStreamingCall { (request, responseObserver) =>
            // TODO: Cast to ServerCallStreamObserver and respect client backpressure via isReady.
            val stream      = f(request)
            val endOfStream = stream.runForeach(processResponse(responseObserver, _)(responseObserver.onNext))
            val completed   = endOfStream.map(_ => responseObserver.onCompleted())
            IO.run(Async.run(completed)).unit.eval
        }

    def bidiStreamingCall[Request, Response: Flat](f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse])(using
        Frame
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncBidiStreamingCall(responseObserver =>
            val observer = BidiRequestStreamObserver.init(f, responseObserver.asInstanceOf[ServerCallStreamObserver[Response]])
            IO.run(observer).eval
        )

    // TODO: Where to put this?
    private[grpc] def processResponse[Response: Flat](
        responseObserver: StreamObserver[?],
        response: Response < GrpcResponse
    )(onSuccess: Response => Unit): Unit < Async =
        Abort.run[StatusException].apply[Response, Async, Any, Nothing](response).map(processResult(responseObserver, _)(onSuccess))

    private[grpc] def processResult[Response](
        responseObserver: StreamObserver[?],
        result: Result[StatusException, Response]
    )(onSuccess: Response => Unit): Unit < Async =
        result match
            // TODO: Why is there a partial match warning here?
            case Result.Success(value) =>
                onSuccess(value)
            case result: Result.Error[StatusException] =>
                processError(responseObserver, result)

    private[grpc] def processCompleted(
        responseObserver: StreamObserver[?],
        result: Result[StatusException, Unit]
    ): Unit < IO =
        result match
            case Result.Success(_) =>
                responseObserver.onCompleted()
            case result: Result.Error[StatusException] =>
                processError(responseObserver, result)

    private[grpc] def processError[Response](
        responseObserver: StreamObserver[?],
        result: Result.Error[StatusException]
    ): Unit < IO =
        result match
            case Result.Fail(s: StatusException) =>
                responseObserver.onError(s)
            case Result.Panic(t) =>
                responseObserver.onError(throwableToStatusException(t))

    private[grpc] def throwableToStatusException(t: Throwable): StatusException =
        t match
            case e: StatusException => e
            case _                  => Status.INTERNAL.withDescription(t.getMessage).withCause(t).asException()

end ServerHandler
