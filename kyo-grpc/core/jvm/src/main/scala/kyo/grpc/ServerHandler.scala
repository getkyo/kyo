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

    import AllowUnsafe.embrace.danger

    // TODO: We should probably implement the ServerCallHandler's ourselves like ZIO does.

    def unary[Request, Response](f: Request => Response < GrpcResponse)(using Frame): ServerCallHandler[Request, Response] =
        ServerCalls.asyncUnaryCall { (request, responseObserver) =>
            val completed = StreamNotifier.notifyObserver(f(request), responseObserver)
            KyoApp.Unsafe.runAndBlock(Duration.Infinity)(completed).getOrThrow
        }

    def clientStreaming[Request: Tag, Response: Tag](f: Stream[Request, GrpcRequest] => Response < GrpcResponse)(using
        Frame
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncClientStreamingCall(responseObserver =>
            // TODO: Double check that the caller does not use the observer concurrently since it is not thread-safe.
            val serverResponseObserver = responseObserver.asInstanceOf[ServerCallStreamObserver[Response]]
            val observer               = RequestStreamObserver.init(f, serverResponseObserver)
            Abort.run(IO.Unsafe.run(observer)).eval.getOrThrow
        )

    def serverStreaming[Request: Tag, Response: Tag](f: Request => Stream[Response, GrpcResponse] < GrpcResponse)(using
        Frame
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncServerStreamingCall { (request, responseObserver) =>
            val completed = StreamNotifier.notifyObserver(Stream.embed(f(request)), responseObserver)
            KyoApp.Unsafe.runAndBlock(Duration.Infinity)(completed).getOrThrow
            // TODO: Remove this.
            println("ServerHandler.serverStreaming Done")
        }

    def bidiStreaming[
        Request: Tag,
        Response: Tag
    ](f: Stream[Request, GrpcRequest] => Stream[Response, GrpcResponse] < GrpcResponse)(using Frame): ServerCallHandler[Request, Response] =
        ServerCalls.asyncBidiStreamingCall(responseObserver =>
            val observer = BidiRequestStreamObserver.init(f, responseObserver.asInstanceOf[ServerCallStreamObserver[Response]])
            Abort.run(IO.Unsafe.run(observer)).eval.getOrThrow
        )

end ServerHandler
