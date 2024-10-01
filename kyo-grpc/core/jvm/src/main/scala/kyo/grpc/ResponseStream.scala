package kyo.grpc

import io.grpc.Grpc
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*

// TODO: What to call this? It's not really a stream.
object ResponseStream:

    private[grpc] def processSingleResponse[Response: Flat](
        responseObserver: StreamObserver[Response],
        response: Response < GrpcResponse
    )(using Frame): Unit < Async =
        val onSuccess = responseObserver.onNext.andThen(_ => responseObserver.onCompleted())
        processResponse(responseObserver, response)(onSuccess)
    end processSingleResponse

    private[grpc] def processMultipleResponses[Response: Flat: Tag](
        responseObserver: StreamObserver[Response],
        responses: Stream[Response, GrpcResponse]
    )(using Frame): Unit < Async =
        Abort.run[StatusException](responses.runForeach(responseObserver.onNext)).map(processCompleted(responseObserver, _))

    private[grpc] def processResponse[Response: Flat](
        responseObserver: StreamObserver[?],
        response: Response < GrpcResponse
    )(onSuccess: Response => Unit)(using Frame): Unit < Async =
        Abort.run[StatusException](response).map(processResult(responseObserver, _)(onSuccess))

    private[grpc] def processResult[Response](
        responseObserver: StreamObserver[?],
        result: Result[StatusException, Response]
    )(onSuccess: Response => Unit): Unit < Async =
        result match
            case Result.Success(value) =>
                onSuccess(value)
            // TODO: Why can't the type arguments be determined?
            case result: Result.Error[StatusException] =>
                processError(responseObserver, result)

    private[grpc] def processCompleted(
        responseObserver: StreamObserver[?],
        result: Result[StatusException, Unit]
    ): Unit < IO =
        result match
            case Result.Success(_) =>
                responseObserver.onCompleted()
            // TODO: Why can't the type arguments be determined?
            case result: Result.Error[StatusException] =>
                processError(responseObserver, result)

    private[grpc] def processError[Response](
        responseObserver: StreamObserver[?],
        result: Result.Error[StatusException]
    ): Unit < IO =
        // TODO: Why is there a partial match warning here?
        result match
            case Result.Fail(s: StatusException) =>
                responseObserver.onError(s)
            case Result.Panic(t) =>
                responseObserver.onError(throwableToStatusException(t))

    // TODO: This doesn't belong here.
    private[grpc] def throwableToStatusException(t: Throwable): StatusException =
        t match
            case e: StatusException => e
            case _                  => Status.INTERNAL.withDescription(t.getMessage).withCause(t).asException()

end ResponseStream
