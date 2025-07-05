package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import kyo.grpc.*

/** A client-side observer that receives a single response from the server.
  *
  * It completes a [[Promise]] with the received response or error for consumption by the client.
  *
  * @param promise
  *   a promise that will be completed with the response or error
  * @tparam Response
  *   the type of the response message
  */
private[kyo] class UnaryResponseStreamObserver[Response](promise: Promise[GrpcFailure, Response])(using Frame, AllowUnsafe)
    extends StreamObserver[Response]:

    override def onNext(response: Response): Unit =
        val complete = promise.completeDiscard(Success(response))
        Abort.run(Sync.Unsafe.run(complete)).eval.getOrThrow
    end onNext

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val result   = Failure(GrpcFailure.fromThrowable(throwable))
        val complete = promise.completeDiscard(result)
        Abort.run(Sync.Unsafe.run(complete)).eval.getOrThrow
    end onError

    override def onCompleted(): Unit =
        val complete = promise.completeDiscard(Failure(StatusException(Status.CANCELLED)))
        Abort.run(Sync.Unsafe.run(complete)).eval.getOrThrow
    end onCompleted

end UnaryResponseStreamObserver
