package kyo.grpc

import io.grpc.ServerCallHandler
import io.grpc.StatusException
import io.grpc.stub.ServerCalls
import io.grpc.stub.ServerCallStreamObserver
import kyo.*

/** Factory for creating gRPC server call handlers that integrate with Kyo.
  *
  * This object provides methods to create server handlers for all four types of gRPC methods: unary, client streaming, server streaming,
  * and bidirectional streaming. Each handler method converts Kyo effects into gRPC-compatible server handlers.
  *
  * All handlers are designed to work with the [[Grpc]] effect type and handle exceptions and stream completion appropriately.
  */
object ServerHandler:

    import AllowUnsafe.embrace.danger

    /** Creates a server handler for unary gRPC calls.
      *
      * A unary call receives a single request and produces a single response. The handler function `f` takes a request and returns a
      * response pending the [[Grpc]] effect.
      *
      * @param f
      *   the handler function that processes the request
      * @tparam Request
      *   the type of the incoming request
      * @tparam Response
      *   the type of the outgoing response
      * @return
      *   a gRPC [[ServerCallHandler]] for unary calls
      */
    def unary[Request, Response](f: Request => Response < Grpc)(using Frame): ServerCallHandler[Request, Response] =
        ServerCalls.asyncUnaryCall { (request, responseObserver) =>
            val response  = f(request)
            val completed = StreamNotifier.notifyObserver(response, responseObserver)
            KyoApp.Unsafe.runAndBlock(Duration.Infinity)(completed).getOrThrow
        }

    /** Creates a server handler for client streaming gRPC calls.
      *
      * A client streaming call receives a stream of requests from the client and produces a single response. The handler function `f` takes
      * a stream of requests and returns a response pending the [[Grpc]] effect.
      *
      * @param f
      *   the handler function that processes the request stream
      * @tparam Request
      *   the type of each request in the stream
      * @tparam Response
      *   the type of the single response
      * @return
      *   a gRPC [[ServerCallHandler]] for client streaming calls
      */
    def clientStreaming[Request, Response](f: Stream[Request, Grpc] => Response < Grpc)(using
        Frame,
        Tag[Emit[Chunk[Request]]]
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncClientStreamingCall(responseObserver =>
            val serverResponseObserver = responseObserver.asInstanceOf[ServerCallStreamObserver[Response]]
            val requestObserver        = RequestStreamObserver.one(f, serverResponseObserver)
            Abort.run(Sync.Unsafe.run(requestObserver)).eval.getOrThrow
        )

    /** Creates a server handler for server streaming gRPC calls.
      *
      * A server streaming call receives a single request and produces a stream of responses. The handler function `f` takes a request and
      * returns a stream of responses pending the [[Grpc]] effect.
      *
      * @param f
      *   the handler function that processes the request and produces a response stream
      * @tparam Request
      *   the type of the single request
      * @tparam Response
      *   the type of each response in the stream
      * @return
      *   a gRPC [[ServerCallHandler]] for server streaming calls
      */
    def serverStreaming[Request, Response](f: Request => Stream[Response, Grpc] < Grpc)(using
        Frame,
        Tag[Emit[Chunk[Response]]]
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncServerStreamingCall { (request, responseObserver) =>
            val responses = Stream.unwrap(f(request))
            val completed = StreamNotifier.notifyObserver(responses, responseObserver)
            KyoApp.Unsafe.runAndBlock(Duration.Infinity)(completed).getOrThrow
        }

    /** Creates a server handler for bidirectional streaming gRPC calls.
      *
      * A bidirectional streaming call receives a stream of requests and produces a stream of responses. The handler function `f` takes a
      * stream of requests and returns a stream of responses pending the [[Grpc]] effect.
      *
      * @param f
      *   the handler function that processes the request stream and produces a response stream
      * @tparam Request
      *   the type of each request in the input stream
      * @tparam Response
      *   the type of each response in the output stream
      * @return
      *   a gRPC [[ServerCallHandler]] for bidirectional streaming calls
      */
    def bidiStreaming[Request, Response](f: Stream[Request, Grpc] => Stream[Response, Grpc] < Grpc)(using
        Frame,
        Tag[Emit[Chunk[Request]]],
        Tag[Emit[Chunk[Response]]]
    ): ServerCallHandler[Request, Response] =
        ServerCalls.asyncBidiStreamingCall(responseObserver =>
            val serverResponseObserver = responseObserver.asInstanceOf[ServerCallStreamObserver[Response]]
            val requestObserver        = RequestStreamObserver.many(f, serverResponseObserver)
            Abort.run(Sync.Unsafe.run(requestObserver)).eval.getOrThrow
        )

end ServerHandler
