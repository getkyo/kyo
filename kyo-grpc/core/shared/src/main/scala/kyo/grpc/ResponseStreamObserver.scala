package kyo.grpc

import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*

/** An 'outbound', client-side observer that receives a stream of responses from the server.
  *
  * It forwards responses to the `responseChannel` for consumption by the client.
  *
  * @param responseChannel
  *   a channel for forwarding received responses
  * @tparam Response
  *   the type of the response messages
  */
private[kyo] class ResponseStreamObserver[Response](
    responseChannel: StreamChannel[Response, GrpcFailure]
)(using Frame, AllowUnsafe) extends StreamObserver[Response]:

    override def onNext(response: Response): Unit =
        val put = responseChannel.put(response)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(put).getOrThrow
    end onNext

    // onError will be the last method called. There will be no call to onCompleted.
    override def onError(throwable: Throwable): Unit =
        val error = GrpcFailure.fromThrowable(throwable)
        val fail  = responseChannel.error(error)
        KyoApp.Unsafe.runAndBlock(Duration.Infinity)(fail).getOrThrow
    end onError

    override def onCompleted(): Unit =
        Sync.Unsafe.evalOrThrow(responseChannel.closeProducer)

end ResponseStreamObserver
