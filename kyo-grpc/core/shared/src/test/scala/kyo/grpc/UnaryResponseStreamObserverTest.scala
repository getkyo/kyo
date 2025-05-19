package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import org.scalactic.TripleEquals.*
import org.scalamock.scalatest.AsyncMockFactory2
import org.scalatest.EitherValues.*
import scala.util.chaining.*

class UnaryResponseStreamObserverTest extends Test:

    "onNext puts value" in run {
        for
            promise  <- Promise.init[GrpcResponse.Errors, String]
            observer <- IO.Unsafe(UnaryResponseStreamObserver[String](promise))
            _        <- IO(observer.onNext("next"))
            result   <- promise.poll
        yield assert(result === Success("next"))
    }

    "onError with status exception fails" in run {
        val exception = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        for
            promise <- Promise.init[GrpcResponse.Errors, String]
            observer <- IO.Unsafe(UnaryResponseStreamObserver[String](promise))
            _ <- IO(observer.onError(statusException))
            result <- promise.poll
        yield assert(result.get.failure.get === statusException)
    }

    "onError with other exception fails" in run {
        val exception       = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        for
            promise  <- Promise.init[GrpcResponse.Errors, String]
            observer <- IO.Unsafe(UnaryResponseStreamObserver[String](promise))
            _        <- IO(observer.onError(exception))
            result   <- promise.poll
        yield assert(result.get.failure.get === statusException)
    }

    "onCompleted completes" in run {
        val statusException = StatusException(Status.CANCELLED)

        for
            promise  <- Promise.init[GrpcResponse.Errors, String]
            observer <- IO.Unsafe(UnaryResponseStreamObserver[String](promise))
            _        <- IO(observer.onCompleted())
            result   <- promise.poll
        yield assert(result.get.failure.get === statusException)
    }

end UnaryResponseStreamObserverTest
