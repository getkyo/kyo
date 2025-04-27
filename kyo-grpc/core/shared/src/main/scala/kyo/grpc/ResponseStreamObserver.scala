package kyo.grpc

import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*

// TODO: This should implement ServerCallStreamObserver.
class ResponseStreamObserver[Response](
    responseChannel: StreamChannel[Response, GrpcResponse.Errors]
)(using Frame, AllowUnsafe) extends StreamObserver[Response]:

    override def onNext(response: Response): Unit =
        val put = responseChannel.put(response)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(put).getOrThrow
    end onNext

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val error = StreamNotifier.throwableToStatusException(throwable)
        val fail = responseChannel.error(error)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow
    end onError

    override def onCompleted(): Unit =
        val close = responseChannel.closeProducer
        Abort.run(IO.Unsafe.run(close)).eval.getOrThrow
    end onCompleted

end ResponseStreamObserver
