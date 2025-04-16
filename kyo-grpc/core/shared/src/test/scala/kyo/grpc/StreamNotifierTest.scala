package kyo.grpc

import io.grpc.*
import io.grpc.stub.StreamObserver
import kyo.*
import org.scalactic.TripleEquals.*
import org.scalamock.scalatest.AsyncMockFactory2

class StreamNotifierTest extends Test with AsyncMockFactory2:

    "notifyObserver with single value success" in run {
        val observer = mock[StreamObserver[Int]]
        val value    = 1

        inSequence:
            (observer.onNext _).expects(value)
            (observer.onCompleted _).expects()
            (observer.onError _).expects(*).never()

        StreamNotifier.notifyObserver(value, observer).map(_ => succeed)
    }

    "notifyObserver with single value failure" in run {
        val observer  = mock[StreamObserver[Int]]
        val exception = new RuntimeException("Test exception")

        inSequence:
            (observer.onNext _).expects(*).never()
            (observer.onCompleted _).expects().never()
            (observer.onError _).expects(exception)

        StreamNotifier.notifyObserver(Abort.fail(exception), observer).map(_ => succeed)
    }

    "notifyObserver with single value panic" in run {
        val observer        = mock[StreamObserver[Int]]
        val exception       = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        inSequence:
            (observer.onNext _).expects(*).never()
            (observer.onCompleted _).expects().never()
            (observer.onError _).expects(argThat[StatusException](_ === statusException))

        StreamNotifier.notifyObserver(Abort.panic(exception), observer).map(_ => succeed)
    }

    "notifyObserver with stream success" in run {
        val observer   = mock[StreamObserver[Int]]
        val successful = Seq(1, 2, 3)
        val values     = Stream.init(successful)

        inSequence:
            successful.foreach: value =>
                (observer.onNext _).expects(value)
            (observer.onCompleted _).expects()
            (observer.onError _).expects(*).never()

        StreamNotifier.notifyObserver(values, observer).map(_ => succeed)
    }

    "notifyObserver with stream failure" in run {
        val observer   = mock[StreamObserver[Int]]
        val exception  = new RuntimeException("Stream failure")
        val successful = Seq(1, 2)
        val values     = Stream.init(successful).concat(Stream.init(Abort.fail(exception)))

        inSequence:
            values.foreach: value =>
                (observer.onNext _).expects(value)
            (observer.onCompleted _).expects().never()
            (observer.onError _).expects(exception)

        StreamNotifier.notifyObserver(values, observer).map(_ => succeed)
    }

    "notifyObserver with stream panic" in run {
        val observer        = mock[StreamObserver[Int]]
        val exception       = new RuntimeException("Stream panic")
        val statusException = StreamNotifier.throwableToStatusException(exception)
        val successful      = Seq(1, 2)
        val values          = Stream.init(successful).concat(Stream.init(Abort.panic(exception)))

        inSequence:
            values.foreach: value =>
                (observer.onNext _).expects(value)
            (observer.onCompleted _).expects().never()
            (observer.onError _).expects(argThat[StatusException](_ === statusException))

        StreamNotifier.notifyObserver(values, observer).map(_ => succeed)
    }

end StreamNotifierTest
