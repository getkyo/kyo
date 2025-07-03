package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*
import org.scalactic.TripleEquals.*
import org.scalamock.scalatest.AsyncMockFactory2
import scala.concurrent.Future

class RequestStreamObserverTest extends Test with AsyncMockFactory2:

    private def foldRequests(requests: Stream[String, Grpc]): String < Grpc =
        requests.into(Sink.collect.map(_.mkString(" ")))

    "onComplete puts result of folded requests" in run {
        val serverObserver = mock[ServerCallStreamObserver[String]]

        def setupExpectations(latch: Latch) =
            Sync.Unsafe {
                serverObserver.onNext
                    .expects("next next")
                    .onCall { _ =>
                        Sync.Unsafe.evalOrThrow(latch.release)
                    }
                    .once()

                (() => serverObserver.onCompleted())
                    .expects()
                    .onCall { _ =>
                        Sync.Unsafe.evalOrThrow(latch.release)
                    }
                    .once()
            }

        for
            latch    <- Latch.init(2)
            _        <- setupExpectations(latch)
            observer <- Sync.Unsafe(RequestStreamObserver.init(foldRequests, serverObserver))
            _        <- Sync.defer(observer.onNext("next"))
            _        <- Sync.defer(observer.onNext("next"))
            _        <- Sync.defer(observer.onCompleted())
            _        <- latch.await
        yield succeed
    }

    "onError with status exception puts error of folded requests" in run {
        val serverObserver = mock[ServerCallStreamObserver[String]]

        val exception       = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        def setupExpectations(latch: Latch) =
            Sync.Unsafe {
                serverObserver.onError
                    .expects(argThat[StatusException](_ === statusException))
                    .onCall { _ =>
                        Sync.Unsafe.evalOrThrow(latch.release)
                    }
                    .once()
            }

        for
            latch    <- Latch.init(1)
            _        <- setupExpectations(latch)
            observer <- Sync.Unsafe(RequestStreamObserver.init(foldRequests, serverObserver))
            _        <- Sync.defer(observer.onNext("next"))
            _        <- Sync.defer(observer.onNext("next"))
            _        <- Sync.defer(observer.onError(statusException))
            _        <- latch.await
        yield succeed
    }

    "onError with other exception puts error of folded requests" in run {
        val serverObserver = mock[ServerCallStreamObserver[String]]

        val exception       = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        def setupExpectations(latch: Latch) =
            Sync.Unsafe {
                serverObserver.onError
                    .expects(argThat[StatusException](_ === statusException))
                    .onCall { _ =>
                        Sync.Unsafe.evalOrThrow(latch.release)
                    }
                    .once()
            }

        for
            latch    <- Latch.init(1)
            _        <- setupExpectations(latch)
            observer <- Sync.Unsafe(RequestStreamObserver.init(foldRequests, serverObserver))
            _        <- Sync.defer(observer.onNext("next"))
            _        <- Sync.defer(observer.onNext("next"))
            _        <- Sync.defer(observer.onError(exception))
            _        <- latch.await
        yield succeed
    }

end RequestStreamObserverTest
