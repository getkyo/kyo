package kyo.bench.arena.grpc

import cats.effect
import cats.effect.IO as CIO
import cats.effect.IO.given
import fs2.grpc.syntax.all.*
import io.grpc
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.*
import kgrpc.bench.*
import kyo.*
import kyo.grpc.*
import scalapb.zio_grpc
import scalapb.zio_grpc.{ScopedServer, ZChannel}
import zio.{Scope, ZIO, stream, given}

import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import scala.language.implicitConversions

object GrpcService:

    given Frame = Frame.internal

    val host = "localhost"
    val size = 10

    def createCatsServer(port: Int, static: Boolean): cats.effect.Resource[CIO, Server] =
        val service = if static then StaticCatsTestService(size) else CatsTestService(size)
        TestServiceFs2Grpc
            .bindServiceResource[CIO](service)
            .flatMap: service =>
                 NettyServerBuilder
                    .forPort(port)
                    .addService(service)
                    .resourceWithShutdown { server =>
                        for {
                            _ <- CIO(server.shutdown())
                            terminated <- CIO.interruptible(server.awaitTermination(30, TimeUnit.SECONDS))
                            _ <- CIO.unlessA(terminated)(CIO.interruptible(server.shutdownNow().awaitTermination()))
                        } yield ()
                    }
                    .evalMap(server => CIO(server.start()))
    end createCatsServer

    def createCatsClient(port: Int): cats.effect.Resource[CIO, TestServiceFs2Grpc[CIO, Metadata]] =
        NettyChannelBuilder
            .forAddress(host, port)
            .usePlaintext()
            .resource[CIO]
            .flatMap(TestServiceFs2Grpc.stubResource[CIO](_))
    end createCatsClient

    def createKyoServer(port: Int, static: Boolean): grpc.Server < (Resource & IO) =
        val service = if static then StaticKyoTestService(size) else KyoTestService(size)
        kyo.grpc.Server.start(port)(_.addService(service))
    end createKyoServer

    def createKyoClient(port: Int): TestService.Client < (Resource & IO) =
        kyo.grpc.Client.channel(host, port)(_.usePlaintext()).map(TestService.client(_))

    def createZioServer(port: Int, static: Boolean): ZIO[Scope, Throwable, zio_grpc.Server] =
        val service = if static then StaticZIOTestService(size) else ZIOTestService(size)
        ScopedServer.fromService(ServerBuilder.forPort(port), service)
    end createZioServer

    def createZioClient(port: Int): ZIO[Scope, Throwable, ZioBench.TestServiceClient] =
        val builder = ManagedChannelBuilder.forAddress(host, port).usePlaintext()
        val channel = ZIO.acquireRelease {
            ZIO.attempt(ZChannel(builder.build(), None, Nil))
        } { c =>
           c.shutdown().flatMap(_ => c.awaitTermination(30.seconds)).ignore
        }
        ZioBench.TestServiceClient.scoped(channel)
    end createZioClient

    def findFreePort(): Int =
        val socket = ServerSocket(0)
        try
            socket.getLocalPort
        finally
            socket.close()
    end findFreePort

end GrpcService

class CatsTestService(size: Int) extends TestServiceFs2Grpc[CIO, Metadata]:

    import cats.effect.*

    override def oneToOne(request: kgrpc.bench.Request, ctx: Metadata): IO[Response] =
        IO.pure(Response(request.message))

    override def oneToMany(request: Request, ctx: Metadata): fs2.Stream[IO, Response] =
        fs2.Stream.chunk(fs2.Chunk.constant(Response(request.message), size))

    override def manyToOne(requests: fs2.Stream[IO, Request], ctx: Metadata): IO[Response] =
        requests
            .compile
            .last
            .map(maybe => Response(maybe.fold("")(_.message)))

    override def manyToMany(requests: fs2.Stream[IO, Request], ctx: Metadata): fs2.Stream[IO, Response] =
        requests.flatMap(oneToMany(_, ctx))

end CatsTestService

class KyoTestService(size: Int)(using Frame) extends TestService:

    override def oneToOne(request: Request): Response < Any =
        Response(request.message)

    override def oneToMany(request: Request): Stream[Response, GrpcResponse] < GrpcResponse =
        Stream.init(Chunk.fill(size)(Response(request.message)))

    override def manyToOne(requests: Stream[Request, GrpcRequest]): Response < GrpcResponse =
        Sink.last.drain(requests).map(maybe => Response(maybe.fold("")(_.message)))

    override def manyToMany(requests: Stream[Request, GrpcRequest]): Stream[Response, GrpcResponse] < GrpcResponse =
        requests.flatMap(oneToMany)

end KyoTestService

class ZIOTestService(size: Int) extends ZioBench.TestService:

    override def oneToOne(request: Request): ZIO[Any, StatusException, Response] =
        ZIO.succeed(Response(request.message))

    override def oneToMany(request: Request): stream.Stream[StatusException, Response] =
        stream.ZStream.fromChunk(zio.Chunk.fill(size)(Response(request.message)))

    override def manyToOne(requests: stream.Stream[StatusException, Request]): zio.IO[StatusException, Response] =
        requests.runLast.map(maybe => Response(maybe.fold("")(_.message)))

    override def manyToMany(requests: stream.Stream[StatusException, Request]): stream.Stream[StatusException, Response] =
        requests.flatMap(oneToMany)

end ZIOTestService

class StaticCatsTestService(size: Int) extends TestServiceFs2Grpc[CIO, Metadata]:

    import cats.effect.*

    private val response = Response("response")
    private val responses = fs2.Chunk.constant(response, size)

    override def oneToOne(request: kgrpc.bench.Request, ctx: Metadata): IO[Response] =
        IO.pure(response)

    override def oneToMany(request: Request, ctx: Metadata): fs2.Stream[IO, Response] =
        fs2.Stream.chunk(responses)

    override def manyToOne(requests: fs2.Stream[IO, Request], ctx: Metadata): IO[Response] =
        requests.compile.drain.map(_ => response)

    override def manyToMany(requests: fs2.Stream[IO, Request], ctx: Metadata): fs2.Stream[IO, Response] =
        requests.flatMap(oneToMany(_, ctx))

end StaticCatsTestService

class StaticKyoTestService(size: Int)(using Frame) extends TestService:

    private val response = Response("response")
    private val responses = Chunk.fill(size)(response)

    override def oneToOne(request: Request): Response < Any =
        response

    override def oneToMany(request: Request): Stream[Response, GrpcResponse] < GrpcResponse =
        Stream.init(responses)

    override def manyToOne(requests: Stream[Request, GrpcRequest]): Response < GrpcResponse =
        requests.discard.andThen(response)

    override def manyToMany(requests: Stream[Request, GrpcRequest]): Stream[Response, GrpcResponse] < GrpcResponse =
        requests.flatMap(oneToMany)

end StaticKyoTestService

class StaticZIOTestService(size: Int) extends ZioBench.TestService:

    private val response = Response("response")
    private val responses = zio.Chunk.fill(size)(response)

    override def oneToOne(request: Request): ZIO[Any, StatusException, Response] =
        ZIO.succeed(response)

    override def oneToMany(request: Request): stream.Stream[StatusException, Response] =
        stream.ZStream.fromChunk(responses)

    override def manyToOne(requests: stream.Stream[StatusException, Request]): zio.IO[StatusException, Response] =
        requests.runDrain.as(response)

    override def manyToMany(requests: stream.Stream[StatusException, Request]): stream.Stream[StatusException, Response] =
        requests.flatMap(oneToMany)

end StaticZIOTestService
