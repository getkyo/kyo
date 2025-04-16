package kyo.grpc

import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*

// TODO: Should this extend another StreamObserver?
class ResponseStreamObserver[Response](
    responseChannel: StreamChannel[Response, GrpcResponse.Errors]
)(using Frame, AllowUnsafe) extends StreamObserver[Response]:

    override def onNext(response: Response): Unit =
        val put = responseChannel.put(response)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(put).getOrThrow
    end onNext

    override def onError(t: Throwable): Unit =
        val fail = responseChannel.fail(StreamNotifier.throwableToStatusException(t))
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow
    end onError

    override def onCompleted(): Unit =
        val close = responseChannel.complete
        Abort.run(IO.Unsafe.run(close)).eval.getOrThrow
    end onCompleted

end ResponseStreamObserver
