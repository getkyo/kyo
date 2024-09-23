package kyo.grpc

import io.grpc.StatusException
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*

class RequestStreamObserver[Request, Response](
    f: Request < GrpcRequest => Option[Response] < GrpcResponse,
    responseObserver: ServerCallStreamObserver[Response]
)(using Frame) extends StreamObserver[Request]:

    override def onNext(request: Request): Unit =
        // TODO: Do a better job of backpressuring here.
        val response  = f(request)
        val completed = ServerHandler.processResponse(responseObserver, response)(_.foreach(responseObserver.onNext))
        IO.run(Async.run(completed)).unit.eval
    end onNext

    override def onError(t: Throwable): Unit =
        responseObserver.onError(t)

    override def onCompleted(): Unit =
        responseObserver.onCompleted()

end RequestStreamObserver
