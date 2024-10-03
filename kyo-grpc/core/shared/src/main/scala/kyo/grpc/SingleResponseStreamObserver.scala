package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import kyo.grpc.*

// TODO: Should this extend one of the other StreamObservers?
class SingleResponseStreamObserver[Response](promise: Promise[GrpcResponse.Errors, Response])(using Frame) extends StreamObserver[Response]:

    override def onNext(value: Response): Unit =
        IO.run(promise.completeUnit(Success(value))).eval

    override def onError(throwable: Throwable): Unit =
        val result = throwable match
            case ex: StatusException => Fail(ex)
            case other               => Panic(other)
        IO.run(promise.completeUnit(result)).eval
    end onError

    override def onCompleted(): Unit =
        IO.run(promise.completeUnit(Fail(StatusException(Status.CANCELLED)))).eval

end SingleResponseStreamObserver
