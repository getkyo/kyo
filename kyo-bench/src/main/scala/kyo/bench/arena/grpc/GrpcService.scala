package kyo.bench.arena.grpc

import cats.effect
import cats.effect.IO.given
import cats.effect.IO as CIO
import fs2.grpc.syntax.all.*
import io.grpc
import io.grpc.{Grpc as _, *}
import io.grpc.StatusException
import io.grpc.netty.shaded.io.grpc.netty.*
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kgrpc.bench.*
import kyo.*
import kyo.grpc.*
import scala.language.implicitConversions
import scalapb.zio_grpc
import scalapb.zio_grpc.ScopedServer
import scalapb.zio_grpc.ZChannel
import zio.Scope
import zio.ZIO
import zio.given
import zio.stream

object GrpcService:

    given Frame = Frame.internal

    val host                     = "localhost"
    val size                     = 10
    val sizeSquared: Int         = size ^ 2
    val message                  = "Hello"
    val request: Request         = Request(message)
    val response: Response       = Response(message)
    val requests: Chunk[Request] = Chunk.fill(GrpcService.size)(Request(message))

    def createCatsServer(port: Int, static: Boolean): cats.effect.Resource[CIO, Server] =
        val service = if static then StaticCatsTestService(size) else CatsTestService(size)
        TestServiceFs2Grpc
            .bindServiceResource[CIO](service)
            .flatMap: service =>
                NettyServerBuilder
                    .forPort(port)
                    .addService(service)
                    .resourceWithShutdown { server =>
                        for
                            _          <- CIO(server.shutdown())
                            terminated <- CIO.interruptible(server.awaitTermination(30, TimeUnit.SECONDS))
                            _          <- CIO.unlessA(terminated)(CIO.interruptible(server.shutdownNow().awaitTermination()))
                        yield ()
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

    def createKyoServer(port: Int, static: Boolean): grpc.Server < (Resource & Sync) =
        val service = if static then StaticKyoTestService(size) else KyoTestService(size)
        kyo.grpc.Server.start(port)(_.addService(service))
    end createKyoServer

    def createKyoClient(port: Int): TestService.Client < (Resource & Sync) =
        TestService.managedClient(host, port)(_.usePlaintext())

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
        end try
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

    override def oneToMany(request: Request): Stream[Response, Grpc] < Grpc =
        Stream.init(Chunk.fill(size)(Response(request.message)))

    override def manyToOne(requests: Stream[Request, Grpc]): Response < Grpc =
        Sink.last.drain(requests).map(maybe => Response(maybe.fold("")(_.message)))

    override def manyToMany(requests: Stream[Request, Grpc]): Stream[Response, Grpc] < Grpc =
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

    private val response  = Response("response")
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

    private val response  = Response("response")
    private val responses = Chunk.fill(size)(response)

    override def oneToOne(request: Request): Response < Any =
        response

    override def oneToMany(request: Request): Stream[Response, Grpc] < Grpc =
        Stream.init(responses)

    override def manyToOne(requests: Stream[Request, Grpc]): Response < Grpc =
        requests.discard.andThen(response)

    override def manyToMany(requests: Stream[Request, Grpc]): Stream[Response, Grpc] < Grpc =
        requests.flatMap(oneToMany)

end StaticKyoTestService

class StaticZIOTestService(size: Int) extends ZioBench.TestService:

    private val response  = Response("response")
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
