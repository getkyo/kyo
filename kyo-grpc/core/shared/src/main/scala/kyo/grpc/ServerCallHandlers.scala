package kyo.grpc

import io.grpc.Metadata
import io.grpc.ServerCallHandler
import io.grpc.Status
import kyo.*
import kyo.grpc.*
import kyo.grpc.internal.BidiStreamingServerCallHandler
import kyo.grpc.internal.ClientStreamingServerCallHandler
import kyo.grpc.internal.ServerStreamingServerCallHandler
import kyo.grpc.internal.UnaryServerCallHandler

type GrpcResponseMeta = Env[SafeMetadata] & Emit[ResponseOptions]

type GrpcHandler[Requests, Responses] = Requests => Responses < (Grpc & Emit[SafeMetadata])

type GrpcHandlerInit[Requests, Responses] = GrpcHandler[Requests, Responses] < GrpcResponseMeta

/** Factory for creating gRPC server call handlers that integrate with Kyo.
  *
  * This object provides methods to create server handlers for all four types of gRPC methods: unary, client streaming, server streaming,
  * and bidirectional streaming. Each handler method converts Kyo effects into gRPC-compatible server handlers.
  *
  * All handlers are designed to work with the [[Grpc]] effect type and handle exceptions and stream completion appropriately.
  */
object ServerCallHandlers:

    // TODO: Update the docs for f
    // TODO: Update the callers to include metadata and response options.

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
    def unary[Request, Response](f: GrpcHandlerInit[Request, Response])(using Frame): ServerCallHandler[Request, Response] =
        UnaryServerCallHandler(f)

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
    def clientStreaming[Request, Response](f: GrpcHandlerInit[Stream[Request, Grpc], Response])(using
        Frame,
        Tag[Emit[Chunk[Request]]]
    ): ServerCallHandler[Request, Response] =
        ClientStreamingServerCallHandler(f)

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
    def serverStreaming[Request, Response](f: GrpcHandlerInit[Request, Stream[Response, Grpc]])(using
        Frame,
        Tag[Emit[Chunk[Response]]]
    ): ServerCallHandler[Request, Response] =
        ServerStreamingServerCallHandler(f)

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
    def bidiStreaming[Request, Response](f: GrpcHandlerInit[Stream[Request, Grpc], Stream[Response, Grpc]])(using
        Frame,
        Tag[Emit[Chunk[Request]]],
        Tag[Emit[Chunk[Response]]]
    ): ServerCallHandler[Request, Response] =
        BidiStreamingServerCallHandler(f)

    // TODO: Inline this.
    private[kyo] def errorStatus(error: Result.Error[Throwable]): Status =
        val t = error.failureOrPanic
        Status.fromThrowable(t)

end ServerCallHandlers
