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
        println(s"ResponseStreamObserver.onNext: $response")
        // TODO: Do a better job of backpressuring here.
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(responseChannel.put(Success(response))).getOrThrow
    end onNext

    override def onError(t: Throwable): Unit =
        println(s"ResponseStreamObserver.onError: $t")
        // TODO: Do a better job of backpressuring here.
        val putAndClose =
            for
                _       <- responseChannel.put(Failure(StreamNotifier.throwableToStatusException(t)))
                isEmpty <- responseChannel.empty
                // TODO: Make sure we close properly everywhere else
                _ <- if isEmpty then responseChannel.close else Kyo.unit
            yield ()
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(putAndClose).getOrThrow
    end onError

    override def onCompleted(): Unit =
        print("ResponseStreamObserver.onCompleted")
        val close =
            for
                _       <- responsesCompleted.set(true)
                isEmpty <- responseChannel.empty
                _       <- if isEmpty then responseChannel.close else Kyo.unit
            yield ()
        Abort.run(IO.Unsafe.run(close)).eval.getOrThrow
    end onCompleted

end ResponseStreamObserver
