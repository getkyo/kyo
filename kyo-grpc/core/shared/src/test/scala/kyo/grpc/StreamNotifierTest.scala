package kyo.grpc

import io.grpc.*
import io.grpc.stub.StreamObserver
import kyo.*
import org.scalactic.TripleEquals.*
import org.scalamock.scalatest.AsyncMockFactory

class StreamNotifierTest extends Test with AsyncMockFactory:

    "notifyObserver with single value success" in run {
        val observer = mock[StreamObserver[Int]]
        val value    = 1

        inSequence:
            (observer.onNext).expects(value)
            (() => observer.onCompleted()).expects()
            (observer.onError).expects(*).never()

        StreamNotifier.notifyObserver(value, observer).map(_ => succeed)
    }

    "notifyObserver with single value failure" in run {
        val observer        = mock[StreamObserver[Int]]
        val exception       = new RuntimeException("Test exception")
        val statusException = GrpcFailure.fromThrowable(exception)

        inSequence:
            (observer.onNext).expects(*).never()
            (() => observer.onCompleted()).expects().never()
            (observer.onError).expects(argThat[StatusException](_ === statusException))

        StreamNotifier.notifyObserver(Abort.fail(exception), observer).map(_ => succeed)
    }

    "notifyObserver with single value panic" in run {
        val observer        = mock[StreamObserver[Int]]
        val exception       = new RuntimeException("Test exception")
        val statusException = GrpcFailure.fromThrowable(exception)

        inSequence:
            (observer.onNext).expects(*).never()
            (() => observer.onCompleted()).expects().never()
            (observer.onError).expects(argThat[StatusException](_ === statusException))

        StreamNotifier.notifyObserver(Abort.panic(exception), observer).map(_ => succeed)
    }

    "notifyObserver with stream success" in run {
        val observer   = mock[StreamObserver[Int]]
        val successful = Seq(1, 2, 3)
        val values     = Stream.init(successful)

        inSequence:
            successful.foreach: value =>
                (observer.onNext).expects(value)
            (() => observer.onCompleted()).expects()
            (observer.onError).expects(*).never()

        StreamNotifier.notifyObserver(values, observer).map(_ => succeed)
    }

    "notifyObserver with stream failure" in run {
        val observer        = mock[StreamObserver[Int]]
        val exception       = new RuntimeException("Stream failure")
        val statusException = GrpcFailure.fromThrowable(exception)
        val successful      = Seq(1, 2)
        val values          = Stream.init(successful).concat(Stream.init(Abort.fail(exception)))

        inSequence:
            values.foreach: value =>
                (observer.onNext).expects(value)
            (() => observer.onCompleted()).expects().never()
            (observer.onError).expects(argThat[StatusException](_ === statusException))

        StreamNotifier.notifyObserver(values, observer).map(_ => succeed)
    }

    "notifyObserver with stream panic" in run {
        val observer        = mock[StreamObserver[Int]]
        val exception       = new RuntimeException("Stream panic")
        val statusException = GrpcFailure.fromThrowable(exception)
        val successful      = Seq(1, 2)
        val values          = Stream.init(successful).concat(Stream.init(Abort.panic(exception)))

        inSequence:
            values.foreach: value =>
                (observer.onNext).expects(value)
            (() => observer.onCompleted()).expects().never()
            (observer.onError).expects(argThat[StatusException](_ === statusException))

        StreamNotifier.notifyObserver(values, observer).map(_ => succeed)
    }

    "notifyObserver handles onNext exceptions" - {
        "single value - onNext throws exception" in run {
            val observer        = mock[StreamObserver[Int]]
            val onNextException = new RuntimeException("onNext failed")
            val statusException = GrpcFailure.fromThrowable(onNextException)

            inSequence:
                (observer.onNext).expects(1).throws(onNextException)
                (() => observer.onCompleted()).expects().never()
                (observer.onError).expects(argThat[StatusException](_ === statusException))

            StreamNotifier.notifyObserver(1, observer).map(_ => succeed)
        }

        "stream - onNext throws exception during streaming" in run {
            val observer        = mock[StreamObserver[Int]]
            val onNextException = new RuntimeException("onNext failed on second element")
            val statusException = GrpcFailure.fromThrowable(onNextException)
            val values          = Stream.init(Seq(1, 2, 3))

            inSequence:
                (observer.onNext).expects(1)
                (observer.onNext).expects(2).throws(onNextException)
                (() => observer.onCompleted()).expects().never()
                (observer.onError).expects(argThat[StatusException](_ === statusException))

            StreamNotifier.notifyObserver(values, observer).map(_ => succeed)
        }
    }

end StreamNotifierTest
