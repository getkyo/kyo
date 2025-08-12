package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import java.util.Locale
import kyo.*
import org.scalactic.TripleEquals.*
import org.scalamock.scalatest.AsyncMockFactory
import scala.concurrent.Future

class RequestStreamObserverTest extends Test with AsyncMockFactory:

    "one" - {

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
                observer <- Sync.Unsafe(RequestStreamObserver.one(foldRequests, serverObserver))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onCompleted())
                _        <- latch.await
            yield succeed
            end for
        }

        "onError with status exception puts error of folded requests" in run {
            val serverObserver = mock[ServerCallStreamObserver[String]]

            val exception       = new RuntimeException("Test exception")
            val statusException = GrpcFailure.fromThrowable(exception)

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
                observer <- Sync.Unsafe(RequestStreamObserver.one(foldRequests, serverObserver))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onError(statusException))
                _        <- latch.await
            yield succeed
            end for
        }

        "onError with other exception puts error of folded requests" in run {
            val serverObserver = mock[ServerCallStreamObserver[String]]

            val exception       = new RuntimeException("Test exception")
            val statusException = GrpcFailure.fromThrowable(exception)

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
                observer <- Sync.Unsafe(RequestStreamObserver.one(foldRequests, serverObserver))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onError(exception))
                _        <- latch.await
            yield succeed
            end for
        }

        "when function returns early, channel is closed and onNext silently discards request" in run {
            val serverObserver = mock[ServerCallStreamObserver[String]]

            def immediateReturn(requests: Stream[String, Grpc]): String < Grpc =
                "immediate result"

            def setupExpectations(latch: Latch) =
                Sync.Unsafe {
                    serverObserver.onNext
                        .expects("immediate result")
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
                observer <- Sync.Unsafe(RequestStreamObserver.one(immediateReturn, serverObserver))
                _        <- latch.await
                result   <- Abort.run(Sync.defer(observer.onNext("this disappears into the either")))
            yield
                // This isn't much of a test. It would pass even the channel wasn't closed.
                // You can change the implementation of onNext to not recover the Closed failure and observe that this will panic as expected.
                assert(result === Result.Success(()))
            end for
        }
    }

    "many" - {
        "onComplete puts result of mapped requests" in run {
            val serverObserver = mock[ServerCallStreamObserver[String]]

            def setupExpectations(latch: Latch) =
                Sync.Unsafe {
                    serverObserver.onNext
                        .expects("NEXT")
                        .onCall { _ =>
                            Sync.Unsafe.evalOrThrow(latch.release)
                        }
                        .twice()

                    (() => serverObserver.onCompleted())
                        .expects()
                        .onCall { _ =>
                            Sync.Unsafe.evalOrThrow(latch.release)
                        }
                        .once()
                }

            for
                latch    <- Latch.init(3)
                _        <- setupExpectations(latch)
                observer <- Sync.Unsafe(RequestStreamObserver.many(mapRequests, serverObserver))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onCompleted())
                _        <- latch.await
            yield succeed
            end for
        }

        "onError with status exception puts error of mapped requests" in run {
            val serverObserver = mock[ServerCallStreamObserver[String]]

            val exception       = new RuntimeException("Test exception")
            val statusException = GrpcFailure.fromThrowable(exception)

            def setupExpectations(latch: Latch) =
                Sync.Unsafe {
                    serverObserver.onNext
                        .expects("NEXT")
                        .onCall { _ =>
                            Sync.Unsafe.evalOrThrow(latch.release)
                        }
                        .twice()

                    serverObserver.onError
                        .expects(argThat[StatusException](_ === statusException))
                        .onCall { _ =>
                            Sync.Unsafe.evalOrThrow(latch.release)
                        }
                        .once()
                }

            for
                latch    <- Latch.init(3)
                _        <- setupExpectations(latch)
                observer <- Sync.Unsafe(RequestStreamObserver.many(mapRequests, serverObserver))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onError(statusException))
                _        <- latch.await
            yield succeed
            end for
        }

        "onError with other exception puts error of mapped requests" in run {
            val serverObserver = mock[ServerCallStreamObserver[String]]

            val exception       = new RuntimeException("Test exception")
            val statusException = GrpcFailure.fromThrowable(exception)

            def setupExpectations(latch: Latch) =
                Sync.Unsafe {
                    serverObserver.onNext
                        .expects("NEXT")
                        .onCall { _ =>
                            Sync.Unsafe.evalOrThrow(latch.release)
                        }
                        .twice()

                    serverObserver.onError
                        .expects(argThat[StatusException](_ === statusException))
                        .onCall { _ =>
                            Sync.Unsafe.evalOrThrow(latch.release)
                        }
                        .once()
                }

            for
                latch    <- Latch.init(3)
                _        <- setupExpectations(latch)
                observer <- Sync.Unsafe(RequestStreamObserver.many(mapRequests, serverObserver))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onNext("next"))
                _        <- Sync.defer(observer.onError(exception))
                _        <- latch.await
            yield succeed
            end for
        }

        "when function returns early, channel is closed and onNext silently discards request" in run {
            val serverObserver = mock[ServerCallStreamObserver[String]]

            def immediateReturn(requests: Stream[String, Grpc]): Stream[String, Grpc] =
                Stream.init(Seq("immediate result"))

            def setupExpectations(latch: Latch) =
                Sync.Unsafe {
                    serverObserver.onNext
                        .expects("immediate result")
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
                observer <- Sync.Unsafe(RequestStreamObserver.many(immediateReturn, serverObserver))
                _        <- latch.await
                result   <- Abort.run(Sync.defer(observer.onNext("this disappears into the either")))
            yield
                // This isn't much of a test. It would pass even the channel wasn't closed.
                // You can change the implementation of onNext to not recover the Closed failure and observe that this will panic as expected.
                assert(result === Result.Success(()))
            end for
        }
    }

    private def foldRequests(requests: Stream[String, Grpc]): String < Grpc =
        requests.into(Sink.collect.map(_.mkString(" ")))

    private def mapRequests(requests: Stream[String, Grpc]): Stream[String, Grpc] =
        requests.map(_.toUpperCase(Locale.ENGLISH))

end RequestStreamObserverTest
