package kyo.grpc

import io.grpc.*
import io.grpc.stub.StreamObserver
import kyo.*
import org.scalactic.TripleEquals.*

class StreamNotifierTest extends Test:

    "notifyObserver with single value success" in run {
        val observer = new TestStreamObserver[Int]
        val value    = 42
        val result   = StreamNotifier.notifyObserver(value, observer)
        result.map: _ =>
            assert(observer.received == List(value))
            assert(observer.completed)
    }

    "notifyObserver with single value failure" in run {
        val observer  = new TestStreamObserver[Int]
        val exception = new RuntimeException("Test exception")
        val result    = StreamNotifier.notifyObserver(Abort.fail(exception), observer)
        result.map: _ =>
            assert(observer.error.contains(exception))
    }

    "notifyObserver with stream success" in run {
        val observer = new TestStreamObserver[Int]
        val values   = Stream.init(Seq(1, 2, 3))
        val result   = StreamNotifier.notifyObserver(values, observer)
        result.map: _ =>
            assert(observer.received == List(1, 2, 3))
            assert(observer.completed)
    }

    "notifyObserver with stream failure" in run {
        val observer  = new TestStreamObserver[Int]
        val exception = new RuntimeException("Stream failure")
        val values    = Stream.init(Seq(1, 2)).concat(Stream.init(Abort.fail(exception)))
        val result    = StreamNotifier.notifyObserver(values, observer)
        result.map: _ =>
            assert(observer.received == List(1, 2))
            assert(observer.error.contains(exception))
    }

    "notifyObserver with stream panic" in run {
        val observer        = new TestStreamObserver[Int]
        val exception       = new RuntimeException("Stream panic")
        val statusException = StreamNotifier.throwableToStatusException(exception)
        val values          = Stream.init(Seq(1, 2)).concat(Stream.init(Abort.panic(exception)))
        val result          = StreamNotifier.notifyObserver(values, observer)
        result.map: _ =>
            assert(observer.received == List(1, 2))
            assert(observer.error.isDefined)
            assert(observer.error.get.isInstanceOf[StatusException])
            val actualError = observer.error.get.asInstanceOf[StatusException]
            assert(actualError === statusException)
    }

    private class TestStreamObserver[A] extends StreamObserver[A]:
        var received: List[A]        = List.empty
        var completed: Boolean       = false
        var error: Option[Throwable] = None

        override def onNext(value: A): Unit =
            received = received :+ value

        override def onError(t: Throwable): Unit =
            error = Some(t)

        override def onCompleted(): Unit =
            completed = true
    end TestStreamObserver

end StreamNotifierTest
