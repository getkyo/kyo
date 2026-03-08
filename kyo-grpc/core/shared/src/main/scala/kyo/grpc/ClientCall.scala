package kyo.grpc

import io.grpc.CallOptions
import io.grpc.ClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.StatusException
import kyo.*
import kyo.grpc.*
import kyo.grpc.internal.ServerStreamingClientCallListener
import kyo.grpc.internal.UnaryClientCallListener

// TODO: Name these.

type GrpcRequestCompletion = Unit < (Env[CallClosed] & Async)

private[grpc] type GrpcResponsesAwaitingCompletion[MaybeResponses] = MaybeResponses < (Emit[GrpcRequestCompletion] & Async)

// Unary and server streaming method calls do not flush the request headers so they are only sent when the request is
// sent. That means that they cannot be used in the creation of the request and can only be provided to the application
// when the response is received.
// TODO: Singular vs plural is confusing here.
type GrpcRequest[Requests] = Requests < (Emit[GrpcRequestCompletion] & Async)

type GrpcRequestsPendingHeaders[Requests] = Requests < (Env[SafeMetadata] & Emit[GrpcRequestCompletion] & Async)

type GrpcRequestsInit[Requests] = GrpcRequestsPendingHeaders[Requests] < (Emit[RequestOptions] & Async)

type GrpcRequestInit[Request] = GrpcRequest[Request] < (Emit[RequestOptions] & Async)

/** Provides client-side gRPC call implementations for different RPC patterns.
  *
  * This object contains methods for executing unary, client streaming, server streaming, and bidirectional streaming gRPC calls using Kyo's
  * effect system.
  */
object ClientCall:

    // TODO:
    //  - unary and serverStreaming need to provide the headers with the response.
    //  - all methods need to provide trailers.

    /** Executes a unary gRPC call.
      *
      * A unary call sends a single request and receives a single response.
      *
      * @param channel
      *   the gRPC channel to use for the call
      * @param method
      *   the method descriptor defining the RPC method
      * @param options
      *   call options for configuring the request
      * @param requestInit
      *   the request message to send
      * @tparam Request
      *   the type of the request message
      * @tparam Response
      *   the type of the response message
      * @return
      *   the response message pending [[Grpc]]
      */
    def unary[Request, Response](
        channel: io.grpc.Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requestInit: GrpcRequestInit[Request]
    )(using Frame): Response < Grpc =
        def start(call: ClientCall[Request, Response], options: RequestOptions): UnaryClientCallListener[Response] < Sync =
            for
                headersPromise    <- Promise.init[Metadata, Any]
                responsePromise   <- Promise.init[Response, Abort[StatusException]]
                completionPromise <- Promise.init[CallClosed, Any]
                readySignal       <- Signal.initRef[Boolean](false)
                listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)
                _ <- Sync.defer(call.start(listener, options.headers.toJava))
                _ <- Sync.defer(options.messageCompression.foreach(call.setMessageCompression))
                _ <- Sync.defer(call.request(1))
            yield listener
        end start

        def sendAndReceive(
            call: ClientCall[Request, Response],
            listener: UnaryClientCallListener[Response],
            requestEffect: GrpcRequest[Request]
        ): GrpcResponsesAwaitingCompletion[Result[GrpcFailure, Response]] =
            for
                // We ignore the ready signal here as we want the request ready as soon as possible,
                // and we will only buffer at most one request.
                // The request was already made when starting.
                request <- requestEffect
                _       <- Sync.defer(call.sendMessage(request))
                _       <- Sync.defer(call.halfClose())
                result  <- listener.responsePromise.getResult
                // TODO: Where is the emit of the effect that waits for completion?
            yield result
        end sendAndReceive

        def processCompletion(listener: UnaryClientCallListener[Response])(completionEffect: GrpcResponsesAwaitingCompletion[Result[
            GrpcFailure,
            Response
        ]]): Response < Grpc =
            Emit.runForeach(completionEffect)(handler =>
                listener.completionPromise.get.map(Env.run(_)(handler))
            ).map(Abort.get)

        def run(call: ClientCall[Request, Response]): Response < Grpc =
            RequestOptions.run(requestInit).map: (options, requestEffect) =>
                for
                    listener <- start(call, options)
                    response <- sendAndReceive(call, listener, requestEffect).handle(
                        processCompletion(listener),
                        cancelOnError(call),
                        cancelOnInterrupt(call)
                    )
                yield response

        Sync.defer(channel.newCall(method, options)).map(run)
    end unary

    /** Executes a client streaming gRPC call.
      *
      * A client streaming call sends multiple requests via a stream and receives a single response. The client can send multiple messages
      * over time, and the server responds with a single message after processing all requests.
      *
      * @param channel
      *   the gRPC channel to use for the call
      * @param method
      *   the method descriptor defining the RPC method
      * @param options
      *   call options for configuring the request
      * @param requestsInit
      *   a stream of request messages to send
      * @tparam Request
      *   the type of the request messages
      * @tparam Response
      *   the type of the response message
      * @return
      *   the response message pending [[Grpc]]
      */
    def clientStreaming[Request: Tag, Response](
        channel: io.grpc.Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requestsInit: GrpcRequestsInit[Stream[Request, Grpc]]
    )(using Frame, Tag[Emit[Chunk[Request]]]): Response < Grpc =
        def start(call: ClientCall[Request, Response], options: RequestOptions): UnaryClientCallListener[Response] < Sync =
            for
                headersPromise    <- Promise.init[Metadata, Any]
                responsePromise   <- Promise.init[Response, Abort[StatusException]]
                completionPromise <- Promise.init[CallClosed, Any]
                readySignal       <- Signal.initRef[Boolean](false)
                listener = UnaryClientCallListener(headersPromise, responsePromise, completionPromise, readySignal)
                _ <- Sync.defer(call.start(listener, options.headers.toJava))
                _ <- Sync.defer(options.messageCompression.foreach(call.setMessageCompression))
            yield listener
        end start

        def processHeaders(
            listener: UnaryClientCallListener[Response],
            requestsEffect: GrpcRequestsPendingHeaders[Stream[Request, Grpc]]
        ): GrpcRequest[Stream[Request, Grpc]] =
            listener.headersPromise.get.map(Env.run(_)(requestsEffect))

        def sendAndClose(
            call: ClientCall[Request, Response],
            listener: UnaryClientCallListener[Response],
            requests: Stream[Request, Grpc]
        ): Result[GrpcFailure, Unit] < Async =
            // Sends the first message regardless of readiness to ensure progress.
            val send = requests.foreach(request =>
                for
                    _ <- Sync.defer(call.sendMessage(request))
                    // There is a race condition between setting the ready signal to false and the listener setting it
                    // to true. Either update may be lost, however, we always check isReady which is the source of
                    // truth. The only case where the signal value matters is when isReady is false. We know that the
                    // signal will still be false, and the listener guarantees that the ready signal will be set to true
                    // when isReady becomes true.
                    _       <- listener.readySignal.set(false)
                    isReady <- Sync.defer(call.isReady)
                    // TODO: We have to handle the case where the listener completes.
                    _ <- if isReady then Kyo.unit else listener.readySignal.next
                yield ()
            )

            Abort.run(send).map((result: Result[GrpcFailure, Unit]) =>
                result match
                    case success: Result.Success[Unit] @unchecked =>
                        Sync.defer(call.halfClose()).andThen(success)
                    case error: Result.Error[GrpcFailure] @unchecked =>
                        Sync.defer(call.cancel("Call was cancelled due to an error.", error.failureOrPanic)).andThen(error)
            )
        end sendAndClose

        def sendAndReceive(
            call: ClientCall[Request, Response],
            listener: UnaryClientCallListener[Response],
            requestsEffect: GrpcRequest[Stream[Request, Grpc]]
        ): GrpcResponsesAwaitingCompletion[Result[GrpcFailure, Response]] =
            for
                requests   <- requestsEffect
                _          <- Sync.defer(call.request(1))
                sendResult <- sendAndClose(call, listener, requests)
                result <-
                    sendResult match
                        case Result.Success(_) => listener.responsePromise.getResult
                        case Result.Failure(e) => Kyo.lift(Result.fail(e))
                        case Result.Panic(e)   => Kyo.lift(Result.panic(e))
            yield result
        end sendAndReceive

        def processCompletion(listener: UnaryClientCallListener[Response])(completionEffect: GrpcResponsesAwaitingCompletion[Result[
            GrpcFailure,
            Response
        ]])
            : Response < Grpc =
            Emit.runForeach(completionEffect)(handler =>
                listener.completionPromise.get.map(Env.run(_)(handler))
            ).map(Abort.get)

        def run(call: ClientCall[Request, Response]): Response < Grpc =
            RequestOptions.run(requestsInit).map: (options, requestsEffect) =>
                for
                    listener <- start(call, options)
                    response <- (for
                        requestsWithHeaders <- processHeaders(listener, requestsEffect)
                        response            <- sendAndReceive(call, listener, requestsWithHeaders)
                    yield response).handle(
                        processCompletion(listener),
                        cancelOnError(call),
                        cancelOnInterrupt(call)
                    )
                yield response

        Sync.defer(channel.newCall(method, options)).map(run)
    end clientStreaming

    /** Executes a server streaming gRPC call.
      *
      * A server streaming call sends a single request and receives multiple responses via a stream. The client sends one message, and the
      * server responds with a stream of messages over time.
      *
      * @param channel
      *   the gRPC channel to use for the call
      * @param method
      *   the method descriptor defining the RPC method
      * @param options
      *   call options for configuring the request
      * @param requestInit
      *   the request message to send
      * @tparam Request
      *   the type of the request message
      * @tparam Response
      *   the type of the response messages
      * @return
      *   a stream of response messages pending [[Grpc]]
      */
    def serverStreaming[Request, Response: Tag](
        channel: io.grpc.Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requestInit: GrpcRequestInit[Request]
    )(using Frame, Tag[Emit[Chunk[Response]]]): Stream[Response, Grpc] =
        def start(call: ClientCall[Request, Response], options: RequestOptions): ServerStreamingClientCallListener[Response] < Sync =
            for
                headersPromise <- Promise.init[Metadata, Any]
                // TODO: What about the Scope?
                // Assumption is that SPSC is fine which I think it is according to gRPC docs.
                responseStream    <- Channel.initUnscoped[Response](options.responseCapacityOrDefault, access = Access.SingleProducerSingleConsumer)
                completionPromise <- Promise.init[CallClosed, Any]
                readySignal       <- Signal.initRef[Boolean](false)
                listener = ServerStreamingClientCallListener(headersPromise, responseStream, completionPromise, readySignal)
                _ <- Sync.defer(call.start(listener, options.headers.toJava))
                _ <- Sync.defer(options.messageCompression.foreach(call.setMessageCompression))
                // TODO: Add tests that ensure that we request the right amount.
                _ <- Sync.defer(call.request(Math.max(1, options.responseCapacityOrDefault)))
            yield listener
        end start

        def sendAndReceive(
            call: ClientCall[Request, Response],
            listener: ServerStreamingClientCallListener[Response],
            requestEffect: GrpcRequest[Request]
        ): GrpcResponsesAwaitingCompletion[Stream[Response, Grpc]] =
            def onChunk(chunk: Chunk[Response]) =
                Sync.defer(call.request(chunk.size))

            for
                // We ignore the ready signal here as we want the request ready as soon as possible,
                // and we will only buffer at most one request.
                request <- requestEffect
                _       <- Sync.defer(call.sendMessage(request))
                _       <- Sync.defer(call.halfClose())
                stream  <- listener.responseChannel.streamUntilClosed().tapChunk(onChunk)
            yield stream
        end sendAndReceive

        def processCompletion(listener: ServerStreamingClientCallListener[Response])(
            completionEffect: GrpcResponsesAwaitingCompletion[Stream[Response, Grpc]]
        ): Stream[Response, Grpc] < Async =
            Emit.run[GrpcRequestCompletion](completionEffect).map: (handlers, responses) =>
                listener.completionPromise.get.map: callClosed =>
                    val completed = handlers.foldLeft(Kyo.unit: Unit < Async): (acc, handler) =>
                        acc.andThen(Env.run(callClosed)(handler))
                    completed.andThen:
                        if callClosed.status.isOk then responses
                        else responses.concat(Stream(Abort.fail(callClosed.asException)))
        end processCompletion

        def run(call: ClientCall[Request, Response]): Stream[Response, Grpc] < Async =
            RequestOptions.run(requestInit).map: (options, requestEffect) =>
                for
                    listener <- start(call, options)
                    responses <- sendAndReceive(call, listener, requestEffect).handle(
                        processCompletion(listener),
                        cancelOnError(call),
                        cancelOnInterrupt(call)
                    )
                yield responses

        Stream.unwrap:
            Sync.defer(channel.newCall(method, options)).map(run)
    end serverStreaming

    /** Executes a bidirectional streaming gRPC call.
      *
      * A bidirectional streaming call allows both client and server to send multiple messages via streams. Both sides can send messages
      * independently and asynchronously, enabling full-duplex communication patterns.
      *
      * @param channel
      *   the gRPC channel to use for the call
      * @param method
      *   the method descriptor defining the RPC method
      * @param options
      *   call options for configuring the request
      * @param requestsInit
      *   a stream of request messages to send
      * @tparam Request
      *   the type of the request messages
      * @tparam Response
      *   the type of the response messages
      * @return
      *   a stream of response messages pending [[Grpc]]
      */
    def bidiStreaming[Request: Tag, Response: Tag](
        channel: io.grpc.Channel,
        method: MethodDescriptor[Request, Response],
        options: CallOptions,
        requestsInit: GrpcRequestsInit[Stream[Request, Grpc]]
    )(using Frame, Tag[Emit[Chunk[Request]]], Tag[Emit[Chunk[Response]]]): Stream[Response, Grpc] =
        def start(call: ClientCall[Request, Response], options: RequestOptions): ServerStreamingClientCallListener[Response] < Sync =
            for
                headersPromise <- Promise.init[Metadata, Any]
                // TODO: What about the Scope?
                // Assumption is that SPSC is fine which I think it is according to gRPC docs.
                responseStream    <- Channel.initUnscoped[Response](options.responseCapacityOrDefault, access = Access.SingleProducerSingleConsumer)
                completionPromise <- Promise.init[CallClosed, Any]
                readySignal       <- Signal.initRef[Boolean](false)
                listener = ServerStreamingClientCallListener(headersPromise, responseStream, completionPromise, readySignal)
                _ <- Sync.defer(call.start(listener, options.headers.toJava))
                _ <- Sync.defer(options.messageCompression.foreach(call.setMessageCompression))
            yield listener
        end start

        def processHeaders(
            listener: ServerStreamingClientCallListener[Response],
            requestsEffect: GrpcRequestsPendingHeaders[Stream[Request, Grpc]]
        ): GrpcRequest[Stream[Request, Grpc]] =
            listener.headersPromise.get.map(Env.run(_)(requestsEffect))

        def sendAndClose(
            call: ClientCall[Request, Response],
            listener: ServerStreamingClientCallListener[Response],
            requests: Stream[Request, Grpc]
        ): Result[GrpcFailure, Unit] < Async =
            // Sends the first message regardless of readiness to ensure progress.
            val send = requests.foreach(request =>
                for
                    _ <- Sync.defer(call.sendMessage(request))
                    // There is a race condition between setting the ready signal to false and the listener setting it
                    // to true. Either update may be lost, however, we always check isReady which is the source of
                    // truth. The only case where the signal value matters is when isReady is false. We know that the
                    // signal will still be false, and the listener guarantees that the ready signal will be set to true
                    // when isReady becomes true.
                    _       <- listener.readySignal.set(false)
                    isReady <- Sync.defer(call.isReady)
                    _       <- if isReady then Kyo.unit else listener.readySignal.next
                yield ()
            )

            Abort.run(send).map((result: Result[GrpcFailure, Unit]) =>
                result match
                    case success: Result.Success[Unit] @unchecked =>
                        Sync.defer(call.halfClose()).andThen(success)
                    case error: Result.Error[GrpcFailure] @unchecked =>
                        Sync.defer(call.cancel("Call was cancelled due to an error.", error.failureOrPanic)).andThen(error)
            )
        end sendAndClose

        def sendAndReceive(
            call: ClientCall[Request, Response],
            listener: ServerStreamingClientCallListener[Response],
            requestsEffect: GrpcRequest[Stream[Request, Grpc]]
        ): GrpcResponsesAwaitingCompletion[Stream[Response, Grpc]] =
            def onResponseChunk(chunk: Chunk[Response]) =
                Sync.defer(call.request(chunk.size))

            for
                requests <- requestsEffect
                _        <- Sync.defer(call.request(1))
                // TODO: Is it fine for this to be unscoped?
                _      <- Fiber.initUnscoped(sendAndClose(call, listener, requests))
                stream <- listener.responseChannel.streamUntilClosed().tapChunk(onResponseChunk)
            yield stream
        end sendAndReceive

        def processCompletion(listener: ServerStreamingClientCallListener[Response])(
            completionEffect: GrpcResponsesAwaitingCompletion[Stream[Response, Grpc]]
        ): Stream[Response, Grpc] < Async =
            Emit.runForeach(completionEffect)(handler =>
                listener.completionPromise.get.map(Env.run(_)(handler))
            )

        def run(call: ClientCall[Request, Response]): Stream[Response, Grpc] < Async =
            RequestOptions.run(requestsInit).map: (options, requestsEffect) =>
                for
                    listener <- start(call, options)
                    responses <- (for
                        requestsWithHeaders <- processHeaders(listener, requestsEffect)
                        responses           <- sendAndReceive(call, listener, requestsWithHeaders)
                    yield responses).handle(
                        processCompletion(listener),
                        cancelOnError(call),
                        cancelOnInterrupt(call)
                    )
                yield responses

        Stream.unwrap:
            Sync.defer(channel.newCall(method, options)).map(run)
    end bidiStreaming

    private def cancelOnError[E <: Throwable : ConcreteTag, Response, S](call: ClientCall[?, ?])(v: => Response < (Abort[E] & S))(using Frame): Response < (Abort[E] & Sync & S) =
        Abort.recoverError[E](error =>
            Sync.defer(call.cancel("Call was cancelled due to an error.", error.failureOrPanic))
                .andThen(Abort.error(error))
        )(v)

    private def cancelOnInterrupt[E, Response](call: ClientCall[?, ?])(v: => Response < (Abort[E] & Async))(using Frame): Response < (Abort[E] & Async) =
        Async.tapFiber(v)(fiber =>
            fiber.onInterrupt(error =>
                val ex = error match
                    case Result.Panic(e) => e
                    case _ => null
                Sync.defer(call.cancel("Kyo Fiber was interrupted.", ex))
            )
        )
    end cancelOnInterrupt

end ClientCall
