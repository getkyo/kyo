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
            Fiber.initUnscoped(effect)
        ))
    end runEffect

    // ===== Server Lifecycle =====

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

    def channel(target: String)(using Frame): ManagedChannel < (Async & Scope) =
        Scope.acquireRelease {
            Sync.defer(NettyChannelBuilder.forTarget(target).usePlaintext().build())
        } { channel =>
            Sync.defer {
                discard(channel.shutdown())
            }
        }

    // ===== Server Call Handlers =====

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

    def serverStreamingCall[Req, Resp](
        grpcChannel: io.grpc.Channel,
        method: MethodDescriptor[Req, Resp],
        request: Req
    )(using Frame, Tag[Emit[Chunk[Resp]]]): Stream[Resp, Async] =
        val ch = kyo.Channel.Unsafe.init[Resp](1024)
        io.grpc.stub.ClientCalls.asyncServerStreamingCall(
            grpcChannel.newCall(method, CallOptions.DEFAULT),
            request,
            new StreamObserver[Resp]:
                override def onNext(value: Resp): Unit =
                    import AllowUnsafe.embrace.danger
                    discard(ch.offer(value))
                override def onError(t: Throwable): Unit =
                    import AllowUnsafe.embrace.danger
                    discard(ch.close())
                override def onCompleted(): Unit =
                    import AllowUnsafe.embrace.danger
                    discard(ch.close())
        )
        ch.safe.streamUntilClosed()

    def clientStreamingCall[Req, Resp](
        grpcChannel: io.grpc.Channel,
        method: MethodDescriptor[Req, Resp],
        requests: Stream[Req, Async]
    )(using Frame, Tag[Emit[Chunk[Req]]]): Resp < (Abort[StatusException] & Async) =
        Async.defer {
            val promise = scala.concurrent.Promise[Resp]()
            val responseObserver = new StreamObserver[Resp]:
                override def onNext(value: Resp): Unit   = discard(promise.success(value))
                override def onError(t: Throwable): Unit = discard(promise.failure(t))
                override def onCompleted(): Unit         = ()

            val requestObserver = io.grpc.stub.ClientCalls.asyncClientStreamingCall(
                grpcChannel.newCall(method, CallOptions.DEFAULT),
                responseObserver
            )

            import AllowUnsafe.embrace.danger
            Sync.Unsafe.evalOrThrow(
                Fiber.initUnscoped {
                    requests.foreach { req =>
                        Sync.defer(requestObserver.onNext(req))
                    }.map { _ =>
                        Sync.defer(requestObserver.onCompleted())
                    }
                }
            )
            Async.fromFuture(promise.future)
        }

    def bidiStreamingCall[Req, Resp](
        grpcChannel: io.grpc.Channel,
        method: MethodDescriptor[Req, Resp],
        requests: Stream[Req, Async]
    )(using Frame, Tag[Emit[Chunk[Req]]], Tag[Emit[Chunk[Resp]]]): Stream[Resp, Async] =
        val ch = kyo.Channel.Unsafe.init[Resp](1024)
        val responseObserver = new StreamObserver[Resp]:
            override def onNext(value: Resp): Unit =
                import AllowUnsafe.embrace.danger
                discard(ch.offer(value))
            override def onError(t: Throwable): Unit =
                import AllowUnsafe.embrace.danger
                discard(ch.close())
            override def onCompleted(): Unit =
                import AllowUnsafe.embrace.danger
                discard(ch.close())

        val requestObserver = io.grpc.stub.ClientCalls.asyncBidiStreamingCall(
            grpcChannel.newCall(method, CallOptions.DEFAULT),
            responseObserver
        )

        import AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped {
                requests.foreach { req =>
                    Sync.defer(requestObserver.onNext(req))
                }.map { _ =>
                    Sync.defer(requestObserver.onCompleted())
                }
            }
        )
        ch.safe.streamUntilClosed()

end Grpcs
