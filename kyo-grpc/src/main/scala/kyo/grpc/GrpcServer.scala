package kyo.grpc

import kyo.*
import kyo.concurrent.*
import kyo.ios.*
import scalapb.grpc.{Grpc, ServiceCompanion}
import io.grpc.{ManagedChannel, ServerBuilder, ServerServiceDefinition, Status, StatusRuntimeException}
import io.grpc.stub.{ServerCalls, StreamObserver}
import com.google.protobuf.{ByteString, Message}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.{Level, Logger}

object GrpcKyo:

  private val logger = Logger.getLogger(getClass.getName)

  /** A Kyo effect for gRPC operations */
  type GrpcIO[T] = >[T | IO]

  /** Wraps a gRPC service definition with Kyo effects */
  trait KyoService:
    def bindService: ServerServiceDefinition

  /** Kyo-based gRPC server */
  class KyoServer(port: Int, services: Seq[KyoService]):
    private var server: io.grpc.Server = null

    def start: GrpcIO[Unit] = IO {
      val builder = ServerBuilder.forPort(port)
      services.foreach { service =>
        builder.addService(service.bindService)
      }
      server = builder.build().start()
      logger.info(s"gRPC server started on port $port")
    }

    def shutdown: GrpcIO[Unit] = IO {
      if server != null then
        server.shutdown()
        logger.info("gRPC server shut down")
    }

    def awaitTermination: GrpcIO[Unit] = IO {
      if server != null then
        server.awaitTermination()
    }

  /** Converts a Kyo effect to a gRPC StreamObserver */
  object KyoStreamObserver:
    def apply[T](onNext: T => GrpcIO[Unit], onError: Throwable => GrpcIO[Unit], onCompleted: GrpcIO[Unit]): StreamObserver[T] =
      new StreamObserver[T]:
        def onNext(value: T): Unit = IO.run(onNext(value))
        def onError(t: Throwable): Unit = IO.run(onError(t))
        def onCompleted(): Unit = IO.run(onCompleted)

  /** Kyo-based gRPC client stub */
  class KyoClientStub[T <: Message](channel: ManagedChannel, companion: ServiceCompanion[T]):
    private val stub = companion.newStub(channel)

    def unaryCall[Req <: Message, Res <: Message](
        method: ServerCalls.UnaryMethod[Req, Res],
        request: Req
    ): GrpcIO[Res] = IO {
      val result = new AtomicReference[Try[Res]](null)
      val latch = new java.util.concurrent.CountDownLatch(1)
      
      stub.unaryCall(method, request, new StreamObserver[Res]:
        def onNext(value: Res): Unit = result.set(Success(value))
        def onError(t: Throwable): Unit = 
          result.set(Failure(t))
          latch.countDown()
        def onCompleted(): Unit = latch.countDown()
      )
      
      latch.await()
      result.get() match
        case Success(value) => value
        case Failure(e) => throw e
    }

    def serverStreamingCall[Req <: Message, Res <: Message](
        method: ServerCalls.ServerStreamingMethod[Req, Res],
        request: Req,
        onChunk: Res => GrpcIO[Unit]
    ): GrpcIO[Unit] = IO {
      val latch = new java.util.concurrent.CountDownLatch(1)
      
      stub.serverStreamingCall(method, request, new StreamObserver[Res]:
        def onNext(value: Res): Unit = IO.run(onChunk(value))
        def onError(t: Throwable): Unit = latch.countDown()
        def onCompleted(): Unit = latch.countDown()
      )
      
      latch.await()
    }

    def clientStreamingCall[Req <: Message, Res <: Message](
        method: ServerCalls.ClientStreamingMethod[Req, Res],
        requests: Seq[Req]
    ): GrpcIO[Res] = IO {
      val result = new AtomicReference[Try[Res]](null)
      val latch = new java.util.concurrent.CountDownLatch(1)
      
      val observer = stub.clientStreamingCall(method, new StreamObserver[Res]:
        def onNext(value: Res): Unit = result.set(Success(value))
        def onError(t: Throwable): Unit = 
          result.set(Failure(t))
          latch.countDown()
        def onCompleted(): Unit = latch.countDown()
      )
      
      requests.foreach(observer.onNext)
      observer.onCompleted()
      
      latch.await()
      result.get() match
        case Success(value) => value
        case Failure(e) => throw e
    }

    def bidiStreamingCall[Req <: Message, Res <: Message](
        method: ServerCalls.BidiStreamingMethod[Req, Res],
        requests: Seq[Req],
        onChunk: Res => GrpcIO[Unit]
    ): GrpcIO[Unit] = IO {
      val latch = new java.util.concurrent.CountDownLatch(1)
      
      val observer = stub.bidiStreamingCall(method, new StreamObserver[Res]:
        def onNext(value: Res): Unit = IO.run(onChunk(value))
        def onError(t: Throwable): Unit = latch.countDown()
        def onCompleted(): Unit = latch.countDown()
      )
      
      requests.foreach(observer.onNext)
      observer.onCompleted()
      
      latch.await()
    }

  /** Helper to create Kyo-based gRPC services */
  object KyoService:
    def apply[Req <: Message, Res <: Message](
        method: ServerCalls.UnaryMethod[Req, Res],
        handler: Req => GrpcIO[Res]
    ): KyoService = new KyoService:
      def bindService: ServerServiceDefinition =
        ServerServiceDefinition.builder("kyo.grpc.service")
          .addMethod(method, ServerCalls.asyncUnaryCall(
            new ServerCalls.UnaryMethod[Req, Res]:
              def invoke(request: Req, responseObserver: StreamObserver[Res]): Unit =
                IO.run {
                  handler(request).map { response =>
                    responseObserver.onNext(response)
                    responseObserver.onCompleted()
                  }.handleError { t =>
                    responseObserver.onError(t)
                  }
                }
          ))
          .build()

    def serverStreaming[Req <: Message, Res <: Message](
        method: ServerCalls.ServerStreamingMethod[Req, Res],
        handler: (Req, StreamObserver[Res]) => GrpcIO[Unit]
    ): KyoService = new KyoService:
      def bindService: ServerServiceDefinition =
        ServerServiceDefinition.builder("kyo.grpc.service")
          .addMethod(method, ServerCalls.asyncServerStreamingCall(
            new ServerCalls.ServerStreamingMethod[Req, Res]:
              def invoke(request: Req, responseObserver: StreamObserver[Res]): Unit =
                IO.run(handler(request, responseObserver))
          ))
          .build()

    def clientStreaming[Req <: Message, Res <: Message](
        method: ServerCalls.ClientStreamingMethod[Req, Res],
        handler: StreamObserver[Res] => GrpcIO[Unit]
    ): KyoService = new KyoService:
      def bindService: ServerServiceDefinition =
        ServerServiceDefinition.builder("kyo.grpc.service")
          .addMethod(method, ServerCalls.asyncClientStreamingCall(
            new ServerCalls.ClientStreamingMethod[Req, Res]:
              def invoke(responseObserver: StreamObserver[Res]): StreamObserver[Req] =
                val observer = new StreamObserver[Req]:
                  def onNext(value: Req): Unit = ()
                  def onError(t: Throwable): Unit = ()
                  def onCompleted(): Unit = IO.run(handler(responseObserver))
                observer
          ))
          .build()

    def bidiStreaming[Req <: Message, Res <: Message](
        method: ServerCalls.BidiStreamingMethod[Req, Res],
        handler: (StreamObserver[Req], StreamObserver[Res]) => GrpcIO[Unit]
    ): KyoService = new KyoService:
      def bindService: ServerServiceDefinition =
        ServerServiceDefinition.builder("kyo.grpc.service")
          .addMethod(method, ServerCalls.asyncBidiStreamingCall(
            new ServerCalls.BidiStreamingMethod[Req, Res]:
              def invoke(responseObserver: StreamObserver[Res]): StreamObserver[Req] =
                val requestObserver = new StreamObserver[Req]:
                  def onNext(value: Req): Unit = ()
                  def onError(t: Throwable): Unit = ()
                  def onCompleted(): Unit = IO.run(handler(requestObserver, responseObserver))
                requestObserver
          ))
          .build()

  /** Error handling utilities */
  object GrpcErrors:
    def toStatusRuntimeException(status: Status, description: String): StatusRuntimeException =
      status.withDescription(