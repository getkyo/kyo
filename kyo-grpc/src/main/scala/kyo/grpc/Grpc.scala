package kyo.grpc

import io.grpc.*
import io.grpc.netty.NettyChannelBuilder
import io.grpc.netty.NettyServerBuilder
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kyo.*
import kyo.Emit
import scala.concurrent.Future

/** Core gRPC support for Kyo. Provides server/channel lifecycle management and bridges between Kyo effects and gRPC's callback-based API.
  *
  * This module enables seamless integration of gRPC services within Kyo's effect system, supporting all four gRPC RPC types:
  *   - Unary (request -> response)
  *   - Server streaming (request -> stream of responses)
  *   - Client streaming (stream of requests -> response)
  *   - Bidirectional streaming (stream of requests <-> stream of responses)
  *
  * Server and channel lifecycle is managed via Kyo's `Scope` effect, ensuring proper resource cleanup.
  */
object Grpcs:

    // ===== Error Handling =====

    private def completeObserver[Resp](observer: StreamObserver[Resp], result: Result[StatusException, Unit]): Unit =
        result match
            case Result.Success(_) =>
                observer.onCompleted()
            case Result.Failure(statusEx) =>
                observer.onError(statusEx.asInstanceOf[StatusException])
            case Result.Panic(ex) =>
                observer.onError(
                    Status.INTERNAL
                        .withDescription(ex.getMessage)
                        .withCause(ex)
                        .asException()
                )

    private def runEffect(effect: Unit < Async)(using Frame): Unit =
        import AllowUnsafe.embrace.danger
        discard(Sync.Unsafe.evalOrThrow(
            KyoApp.runAndBlock(Duration.Infinity)(effect)
        ))
    end runEffect

    // ===== Server Lifecycle =====

    /** Creates a gRPC server bound to the given port with the provided services.
      *
      * The server lifecycle is managed by `Scope` -- it shuts down automatically when the enclosing scope exits. Use port 0 to let the OS
      * assign an available port; the actual port can be retrieved via `server.getPort`.
      *
      * @param port
      *   The port to bind to (0 for any available port)
      * @param services
      *   The service definitions to register
      * @return
      *   A server managed by Scope
      */
    def server(
        port: Int,
        services: Seq[ServerServiceDefinition]
    )(using Frame): Server < (Async & Scope) =
        Scope.acquireRelease {
            Sync.defer {
                val builder = NettyServerBuilder.forPort(port)
                services.foreach(ssd => discard(builder.addService(ssd)))
                builder.build().start()
            }
        } { server =>
            Sync.defer {
                discard(server.shutdown())
            }
        }

    // ===== Channel Lifecycle =====

    /** Creates a gRPC channel connected to the given target.
      *
      * The channel lifecycle is managed by `Scope` -- it shuts down automatically when the enclosing scope exits. Uses plaintext (no TLS)
      * by default.
      *
      * @param target
      *   The target address (e.g., "localhost:8080")
      * @return
      *   A channel managed by Scope
      */
    def channel(target: String)(using Frame): ManagedChannel < (Async & Scope) =
        Scope.acquireRelease {
            Sync.defer(NettyChannelBuilder.forTarget(target).usePlaintext().build())
        } { channel =>
            Sync.defer {
                discard(channel.shutdown())
            }
        }

    // ===== Server Call Handlers =====

    /** Creates a unary handler that bridges a Kyo effect to gRPC's unary call.
      *
      * The handler receives a single request and must produce a single response. Errors are automatically converted to gRPC status
      * exceptions.
      *
      * @param method
      *   The method descriptor
      * @param handler
      *   A function from request to response effect
      * @return
      *   A server method definition ready to be registered
      */
    def unaryHandler[Req, Resp](
        method: MethodDescriptor[Req, Resp],
        handler: Req => Resp < (Abort[StatusException] & Async)
    )(using Frame): ServerMethodDefinition[Req, Resp] =
        ServerMethodDefinition.create(
            method,
            ServerCalls.asyncUnaryCall(
                new ServerCalls.UnaryMethod[Req, Resp]:
                    override def invoke(request: Req, observer: StreamObserver[Resp]): Unit =
                        runEffect {
                            Abort.run[StatusException](handler(request)).map {
                                case Result.Success(resp) =>
                                    observer.onNext(resp)
                                    observer.onCompleted()
                                case Result.Failure(statusEx) =>
                                    observer.onError(statusEx.asInstanceOf[StatusException])
                                case Result.Panic(ex) =>
                                    observer.onError(
                                        Status.INTERNAL
                                            .withDescription(ex.getMessage)
                                            .withCause(ex)
                                            .asException()
                                    )
                            }
                        }
                    end invoke
            )
        )

    /** Creates a server-streaming handler that bridges a Kyo Stream to gRPC's server streaming call.
      *
      * The handler receives a single request and produces a stream of responses. Each element is sent to the client as it becomes
      * available.
      *
      * @param method
      *   The method descriptor
      * @param handler
      *   A function from request to a stream of responses
      * @return
      *   A server method definition ready to be registered
      */
    def serverStreamingHandler[Req, Resp](
        method: MethodDescriptor[Req, Resp],
        handler: Req => Stream[Resp, Abort[StatusException] & Async]
    )(using Frame, Tag[Emit[Chunk[Resp]]]): ServerMethodDefinition[Req, Resp] =
        ServerMethodDefinition.create(
            method,
            ServerCalls.asyncServerStreamingCall(
                new ServerCalls.ServerStreamingMethod[Req, Resp]:
                    override def invoke(request: Req, observer: StreamObserver[Resp]): Unit =
                        runEffect {
                            Abort.run[StatusException] {
                                handler(request).foreach { resp =>
                                    observer.onNext(resp)
                                }
                            }.map(r => completeObserver(observer, r))
                        }
                    end invoke
            )
        )

    /** Creates a client-streaming handler that bridges a Kyo Stream (from client requests) to a single response.
      *
      * Uses `Channel.Unsafe` internally to bridge gRPC's callback-based `StreamObserver` into Kyo's `Stream` abstraction.
      *
      * @param method
      *   The method descriptor
      * @param handler
      *   A function from a stream of requests to a response effect
      * @return
      *   A server method definition ready to be registered
      */
    def clientStreamingHandler[Req, Resp](
        method: MethodDescriptor[Req, Resp],
        handler: Stream[Req, Async] => Resp < (Abort[StatusException] & Async)
    )(using Frame, Tag[Emit[Chunk[Req]]]): ServerMethodDefinition[Req, Resp] =
        ServerMethodDefinition.create(
            method,
            ServerCalls.asyncClientStreamingCall(
                new ServerCalls.ClientStreamingMethod[Req, Resp]:
                    override def invoke(observer: StreamObserver[Resp]): StreamObserver[Req] =
                        import AllowUnsafe.embrace.danger
                        val ch     = kyo.Channel.Unsafe.init[Req](1024)
                        val stream = ch.safe.streamUntilClosed()

                        discard(Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped {
                                Abort.run[StatusException](handler(stream)).map {
                                    case Result.Success(resp) =>
                                        observer.onNext(resp)
                                        observer.onCompleted()
                                    case Result.Failure(statusEx) =>
                                        observer.onError(statusEx.asInstanceOf[StatusException])
                                    case Result.Panic(ex) =>
                                        observer.onError(
                                            Status.INTERNAL
                                                .withDescription(ex.getMessage)
                                                .withCause(ex)
                                                .asException()
                                        )
                                }
                            }
                        ))

                        new StreamObserver[Req]:
                            override def onNext(value: Req): Unit =
                                import AllowUnsafe.embrace.danger
                                discard(ch.offer(value))
                            override def onError(t: Throwable): Unit =
                                import AllowUnsafe.embrace.danger
                                discard(ch.close())
                            override def onCompleted(): Unit =
                                import AllowUnsafe.embrace.danger
                                discard(ch.close())
                        end new
                    end invoke
            )
        )

    /** Creates a bidirectional-streaming handler that bridges two Kyo Streams.
      *
      * The handler receives a stream of requests and produces a stream of responses. Both streams operate concurrently.
      *
      * @param method
      *   The method descriptor
      * @param handler
      *   A function from a request stream to a response stream
      * @return
      *   A server method definition ready to be registered
      */
    def bidiStreamingHandler[Req, Resp](
        method: MethodDescriptor[Req, Resp],
        handler: Stream[Req, Async] => Stream[Resp, Abort[StatusException] & Async]
    )(using Frame, Tag[Emit[Chunk[Req]]], Tag[Emit[Chunk[Resp]]]): ServerMethodDefinition[Req, Resp] =
        ServerMethodDefinition.create(
            method,
            ServerCalls.asyncBidiStreamingCall(
                new ServerCalls.BidiStreamingMethod[Req, Resp]:
                    override def invoke(observer: StreamObserver[Resp]): StreamObserver[Req] =
                        import AllowUnsafe.embrace.danger
                        val ch         = kyo.Channel.Unsafe.init[Req](1024)
                        val reqStream  = ch.safe.streamUntilClosed()
                        val respStream = handler(reqStream)

                        discard(Sync.Unsafe.evalOrThrow(
                            Fiber.initUnscoped {
                                Abort.run[StatusException] {
                                    respStream.foreach { resp =>
                                        observer.onNext(resp)
                                    }
                                }.map(r => completeObserver(observer, r))
                            }
                        ))

                        new StreamObserver[Req]:
                            override def onNext(value: Req): Unit =
                                import AllowUnsafe.embrace.danger
                                discard(ch.offer(value))
                            override def onError(t: Throwable): Unit =
                                import AllowUnsafe.embrace.danger
                                discard(ch.close())
                            override def onCompleted(): Unit =
                                import AllowUnsafe.embrace.danger
                                discard(ch.close())
                        end new
                    end invoke
            )
        )

    // ===== Client Call Helpers =====

    /** Performs a unary gRPC call and returns the result as a Kyo effect.
      *
      * The call is executed asynchronously and the result is lifted into Kyo's `Async` effect. gRPC errors are surfaced as
      * `Abort[StatusException]`.
      *
      * @param grpcChannel
      *   The channel to use for the call
      * @param method
      *   The method descriptor
      * @param request
      *   The request message
      * @return
      *   The response wrapped in Async and Abort effects
      */
    def unaryCall[Req, Resp](
        grpcChannel: io.grpc.Channel,
        method: MethodDescriptor[Req, Resp],
        request: Req
    )(using Frame): Resp < (Abort[StatusException] & Async) =
        Async.defer {
            val promise = scala.concurrent.Promise[Resp]()
            io.grpc.stub.ClientCalls.asyncUnaryCall(
                grpcChannel.newCall(method, CallOptions.DEFAULT),
                request,
                new StreamObserver[Resp]:
                    override def onNext(value: Resp): Unit =
                        discard(promise.success(value))
                    override def onError(t: Throwable): Unit =
                        discard(promise.failure(t))
                    override def onCompleted(): Unit = ()
            )
            Async.fromFuture(promise.future)
        }.map(resp => resp)

end Grpcs
