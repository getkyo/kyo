package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Result.*

// TODO: Should this extend another StreamObserver?
class ResponseStreamObserver[Response](
    responseChannel: Channel[Result[GrpcResponse.Errors, Response]],
    responsesCompleted: AtomicBoolean
)(using Frame) extends StreamObserver[Response]:

    override def onNext(response: Response): Unit =
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(responseChannel.put(Success(response)))).unit.eval

    override def onError(t: Throwable): Unit =
        // TODO: Do a better job of backpressuring here.
        IO.run(Async.run(responseChannel.put(Fail(StreamNotifier.throwableToStatusException(t))))).unit.eval

    override def onCompleted(): Unit =
        IO.run(responsesCompleted.set(true)).unit.eval

end ResponseStreamObserver
