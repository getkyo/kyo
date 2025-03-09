package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*
import org.checkerframework.checker.units.qual.s

// TODO: Should this extend another StreamObserver?
class ResponseStreamObserver[Response](
    responseChannel: Channel[Result[GrpcResponse.Errors, Response]],
    responsesCompleted: AtomicBoolean
)(using Frame, AllowUnsafe) extends StreamObserver[Response]:

    override def onNext(response: Response): Unit =
        // TODO: Remove this.
        println(s"ResponseStreamObserver.onNext: $response")
        val put = responseChannel.put(Success(response))
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(put).getOrThrow
    end onNext

    override def onError(t: Throwable): Unit =
        // TODO: Remove this.
        println(s"ResponseStreamObserver.onError: $t")
        val putAndClose =
            for
                _ <- responseChannel.put(Failure(StreamNotifier.throwableToStatusException(t)))
                _ <- responsesCompleted.set(true)
            yield ()
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(putAndClose).getOrThrow
    end onError

    override def onCompleted(): Unit =
        // TODO: Remove this.
        print("ResponseStreamObserver.onCompleted")
        val close =
            for
                // TODO: Does this actually work? Seems like there might be a race condition.
                _       <- IO(responsesCompleted.set(true))
                isEmpty <- responseChannel.empty
                // TODO: Make sure we close properly everywhere else
                _ <- if isEmpty then responseChannel.close else Kyo.unit
            yield ()
        Abort.run(IO.Unsafe.run(close)).eval.getOrThrow
    end onCompleted

end ResponseStreamObserver
