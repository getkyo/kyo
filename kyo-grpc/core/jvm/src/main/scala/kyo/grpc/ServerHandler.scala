package kyo.grpc

import io.grpc.ServerCallHandler
import io.grpc.Status
import io.grpc.StatusException
import io.grpc.stub.ServerCalls
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import kyo.*

// TODO: Rename.
object ServerHandler:

    // TODO: We should probably implement the ServerCallHandler's ourselves like ZIO does.

    def unary[Request, Response: Flat](f: Request => Response < GrpcResponse)(using Frame): ServerCallHandler[Request, Response] =
        ServerCalls.asyncUnaryCall { (request, responseObserver) =>
            val completed = StreamNotifier.notifyObserver(f(request), responseObserver)
            IO.run(Async.run(completed)).unit.eval
        }

    def clientStreaming[Request: Tag, Response: Flat: Tag](f: Stream[Request, GrpcRequest] => Response < GrpcResponse)(using
        Frame
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncClientStreamingCall(responseObserver =>
            val serverResponseObserver = responseObserver.asInstanceOf[ServerCallStreamObserver[Response]]
            val observer               = RequestStreamObserver.init(f, serverResponseObserver)
            IO.run(observer).eval
        )

    def serverStreaming[Request: Tag, Response: Flat: Tag](f: Request => Stream[Response, GrpcResponse])(using
        Frame
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncServerStreamingCall { (request, responseObserver) =>
            val completed = StreamNotifier.notifyObserver(f(request), responseObserver)
            IO.run(Async.run(completed)).unit.eval
        }

    def bidiStreaming[Request: Tag, Response: Flat: Tag](f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse])(using
        Frame
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncBidiStreamingCall(responseObserver =>
            val observer = BidiRequestStreamObserver.init(f, responseObserver.asInstanceOf[ServerCallStreamObserver[Response]])
            IO.run(observer).eval
        )

end ServerHandler
