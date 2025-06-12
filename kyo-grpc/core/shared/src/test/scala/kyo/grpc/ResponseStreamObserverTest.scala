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

class ResponseStreamObserverTest extends Test with AsyncMockFactory2:

    "onNext puts value" in run {
        val channel  = mock[StreamChannel[String, GrpcResponse.Errors]]
        val observer = IO.Unsafe(ResponseStreamObserver[String](channel))

        (channel.put(_: String)(using _: Frame))
            .expects("next", *)
            .returns(())
            .once()

        observer.map(_.onNext("next")).map(_ => succeed)
    }

    "onError with status exception fails" in run {
        val channel = mock[StreamChannel[String, GrpcResponse.Errors]]
        val observer = IO.Unsafe(ResponseStreamObserver[String](channel))
        val exception = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        (channel.error(_: GrpcResponse.Errors)(using _: Frame))
            .expects(argThat[StatusException](x => {
                statusExceptionEquality.areEqual(x, statusException)
            }), *)
            .returns(())
            .once()

        observer.map(_.onError(statusException)).map(_ => succeed)
    }

    "onError with other exception fails" in run {
        val channel         = mock[StreamChannel[String, GrpcResponse.Errors]]
        val observer        = IO.Unsafe(ResponseStreamObserver[String](channel))
        val exception       = new RuntimeException("Test exception")
        val statusException = StreamNotifier.throwableToStatusException(exception)

        (channel.error(_: GrpcResponse.Errors)(using _: Frame))
            .expects(argThat[StatusException](_ === statusException), *)
            .returns(())
            .once()

        observer.map(_.onError(exception)).map(_ => succeed)
    }

    "onCompleted completes" in run {
        val channel  = mock[StreamChannel[String, GrpcResponse.Errors]]
        val observer = IO.Unsafe(ResponseStreamObserver[String](channel))

        (channel.closeProducer(using _: Frame))
            .expects(*)
            .returns(())
            .once()

        observer.map(_.onCompleted()).map(_ => succeed)
    }

end ResponseStreamObserverTest
