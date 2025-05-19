package kyo.grpc

import io.grpc.{Status, StatusException}
import io.grpc.stub.{ServerCallStreamObserver, StreamObserver}
import kyo.*
import org.scalactic.TripleEquals.*
import org.scalamock.scalatest.AsyncMockFactory2

import java.util.Locale
import scala.concurrent.Future

class BidiRequestStreamObserverTest extends Test with AsyncMockFactory2:

    private def mapRequests(requests: Stream[String, GrpcRequest]): Stream[String, GrpcResponse] =
        requests.map(_.toUpperCase(Locale.ENGLISH))

    "onComplete puts result of mapped requests" in run {
        val serverObserver = mock[ServerCallStreamObserver[String]]

        def setupExpectations(latch: Latch) =
            IO.Unsafe {
                serverObserver.onNext
                    .expects("NEXT")
                    .onCall { _ =>
                        IO.Unsafe.evalOrThrow(latch.release)
                    }
                    .twice()

                (() => serverObserver.onCompleted())
                    .expects()
                    .onCall { _ =>
                        IO.Unsafe.evalOrThrow(latch.release)
                    }
                    .once()
            }

        for
            latch    <- Latch.init(3)
            _        <- setupExpectations(latch)
            observer <- IO.Unsafe(BidiRequestStreamObserver.init(mapRequests, serverObserver))
            _        <- IO(observer.onNext("next"))
            _        <- IO(observer.onNext("next"))
            _        <- IO(observer.onCompleted())
            _        <- latch.await
        yield succeed
    }

    "onError with status exception puts error of mapped requests" in run {
        val serverObserver = mock[ServerCallStreamObserver[String]]

        val exception       = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        def setupExpectations(latch: Latch) =
            IO.Unsafe {
                serverObserver.onNext
                    .expects("NEXT")
                    .onCall { _ =>
                        IO.Unsafe.evalOrThrow(latch.release)
                    }
                    .twice()

                serverObserver.onError
                    .expects(argThat[StatusException](_ === statusException))
                    .onCall { _ =>
                        IO.Unsafe.evalOrThrow(latch.release)
                    }
                    .once()
            }

        for
            latch    <- Latch.init(3)
            _        <- setupExpectations(latch)
            observer <- IO.Unsafe(BidiRequestStreamObserver.init(mapRequests, serverObserver))
            _        <- IO(observer.onNext("next"))
            _        <- IO(observer.onNext("next"))
            _        <- IO(observer.onError(statusException))
            _        <- latch.await
        yield succeed
    }

    "onError with other exception puts error of mapped requests" in run {
        val serverObserver = mock[ServerCallStreamObserver[String]]

        val exception       = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        def setupExpectations(latch: Latch) =
            IO.Unsafe {
                serverObserver.onNext
                    .expects("NEXT")
                    .onCall { _ =>
                        IO.Unsafe.evalOrThrow(latch.release)
                    }
                    .twice()

                serverObserver.onError
                    .expects(argThat[StatusException](_ === statusException))
                    .onCall { _ =>
                        IO.Unsafe.evalOrThrow(latch.release)
                    }
                    .once()
            }

        for
            latch    <- Latch.init(3)
            _        <- setupExpectations(latch)
            observer <- IO.Unsafe(BidiRequestStreamObserver.init(mapRequests, serverObserver))
            _        <- IO(observer.onNext("next"))
            _        <- IO(observer.onNext("next"))
            _        <- IO(observer.onError(exception))
            _        <- latch.await
        yield succeed
    }

end BidiRequestStreamObserverTest
