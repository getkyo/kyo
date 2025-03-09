package kyo.grpc

import io.grpc.Status
import io.grpc.StatusException
import io.grpc.StatusRuntimeException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import org.scalatest.EitherValues.*
import scala.util.chaining.*

class ResponseStreamObserverTest extends Test:

    private def initObserver =
        for
            channel   <- StreamChannel.init[String, GrpcResponse.Errors]
            completed <- AtomicBoolean.init(false)
            observer  <- IO.Unsafe(ResponseStreamObserver[String](channel, completed))
        yield (channel, completed, observer)

    "ResponseStreamObserver" - {
        "onNext" - {
            "should put a Success value to the channel" in run {
                for
                    (channel, completed, observer) <- initObserver
                    _                              <- IO(observer.onNext("test"))
                    result                         <- channel.take
                    completedValue                 <- completed.get
                yield
                    assert(result == Success("test"))
                    assert(!completedValue)
            }

            // TODO: Test multiple values

            "should handle backpressure when channel is full" in runNotJS {
                for
                    (channel, completed, observer) <- initObserver
                    _                              <- Loop.repeat(StreamChannel.Capacity)(IO(observer.onNext("test")))
                    // Start a fiber that will call onNext and be blocked.
                    fiber <- Async.run(IO(observer.onNext("full")))
                    _     <- Async.sleep(10.millis)
                    // Check that fiber is blocked.
                    isDone <- fiber.done
                    // Take the first value to unblock the fiber.
                    _ <- channel.take
                    // Wait for fiber to complete.
                    _ <- fiber.block(timeout)
                yield assert(!isDone)
            }
        }

        "onError" - {
            "should put a StatusException to the channel and set completed flag" in run {
                for
                    (channel, completed, observer) <- initObserver
                    exception = new StatusException(Status.INVALID_ARGUMENT.withDescription("error"))
                    _              <- IO(observer.onError(exception))
                    result         <- channel.take
                    completedValue <- completed.get
                yield
                    assert(result.isFailure)
                    assert(result.failure.get == exception)
                    assert(completedValue)
            }

            "should put a StatusRuntimeException to the channel and set completed flag" in run {
                for
                    (channel, completed, observer) <- initObserver
                    exception = new StatusRuntimeException(Status.UNIMPLEMENTED.withDescription("error"))
                    _              <- IO(observer.onError(exception))
                    result         <- channel.take
                    completedValue <- completed.get
                yield
                    assert(result.isFailure)
                    assert(result.failure.get == exception)
                    assert(completedValue)
            }

            "should put a RuntimeException to the channel and set completed flag" in run {
                for
                    (channel, completed, observer) <- initObserver
                    exception = new StatusRuntimeException(Status.UNIMPLEMENTED.withDescription("error"))
                    _              <- IO(observer.onError(exception))
                    result         <- channel.take
                    completedValue <- completed.get
                yield
                    assert(result.isFailure)
                    assert(result.failure.get == Status.INTERNAL.withDescription(exception.getMessage).withCause(exception).asException())
                    assert(completedValue)
            }
        }

        "onCompleted" - {
            "should set completed flag and close empty channel" in run {
                for
                    (channel, completed, observer) <- initObserver
                    _                              <- IO(observer.onCompleted())
                    completedValue                 <- completed.get
                    isClosed                       <- channel.closed
                yield
                    assert(completedValue)
                    assert(isClosed)
            }

            "should set completed flag but not close non-empty channel" in run {
                for
                    (channel, completed, observer) <- initObserver
                    _                              <- IO(observer.onNext("test"))
                    _                              <- IO(observer.onCompleted())
                    completedValue                 <- completed.get
                    isClosed                       <- channel.closed
                    value                          <- channel.take
                yield
                    assert(completedValue)
                    assert(!isClosed)
                    assert(value == Success("test"))
            }
        }

        "integration" - {
            "should handle a normal response sequence correctly" in run {
                for
                    (channel, completed, observer) <- initObserver
                    _                              <- IO(observer.onNext("response-1"))
                    _                              <- IO(observer.onNext("response-2"))
                    _                              <- IO(observer.onNext("response-3"))
                    _                              <- IO(observer.onCompleted())
                    value1                         <- channel.take
                    value2                         <- channel.take
                    value3                         <- channel.take
                    completedValue                 <- completed.get
                    isClosed                       <- channel.closed
                yield
                    assert(value1 == Success("response-1"))
                    assert(value2 == Success("response-2"))
                    assert(value3 == Success("response-3"))
                    assert(completedValue)
                    assert(isClosed)
            }

            "should handle error during streaming" in run {
                for
                    (channel, completed, observer) <- initObserver
                    _                              <- IO(observer.onNext("response-1"))
                    _                              <- IO(observer.onNext("response-2"))
                    _                              <- IO(observer.onError(new RuntimeException("stream-error")))
                    // Try calling onNext after onError - should still put to channel
                    // but this is typically not done in real gRPC
                    _              <- IO(observer.onNext("response-after-error"))
                    value1         <- channel.take
                    value2         <- channel.take
                    value3         <- channel.take
                    value4         <- channel.take
                    completedValue <- completed.get
                yield
                    assert(value1 == Success("response-1"))
                    assert(value2 == Success("response-2"))
                    assert(value3.isFailure)
                    assert(value4 == Success("response-after-error"))
                    assert(completedValue)
            }

            // "should work correctly with the stream() method of Channel" in run {
            //     for
            //         (channel, completed, observer) <- initObserver
            //         fiber <- Async.run {
            //             IO {
            //                 observer.onNext("response-1")
            //                 observer.onNext("response-2")
            //                 observer.onNext("response-3")
            //                 observer.onCompleted()
            //             }
            //         }
            //         // Create a stream that transforms the Result values
            //         stream = channel.stream()
            //             .takeWhile(result => !completed.get || result.isSuccess)
            //             .map {
            //                 case Success(value) => value
            //                 case Failure(ex)    => s"Error: ${ex.getMessage}"
            //             }
            //         results <- stream.run
            //     yield assert(results == Chunk("response-1", "response-2", "response-3"))
            // }
        }
    }
end ResponseStreamObserverTest
