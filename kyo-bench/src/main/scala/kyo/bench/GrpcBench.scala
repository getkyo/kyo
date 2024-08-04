package kyo.bench

import io.grpc.StatusException
import org.http4s.ember.client.EmberClientBuilder
import zio.ZIO

class GrpcBench extends Bench.ForkOnly("pong"):

    override val zioRuntimeLayer = super.zioRuntimeLayer.merge(zio.http.Client.default)

    val url = TestHttpServer.start(1)

    lazy val catsClient =
        import cats.effect.*
        import cats.effect.unsafe.implicits.global
        EmberClientBuilder.default[IO].build.allocated.unsafeRunSync()._1
    end catsClient

    val catsUrl =
        import org.http4s.*
        Uri.fromString(url).toOption.get

    def catsBench() =
        import cats.effect.*

        catsClient.expect[String](catsUrl)
    end catsBench

    lazy val kyoClient =
        import kyo.*
        PlatformBackend.default

    val kyoUrl =
        import sttp.client3.*
        uri"$url"

    override def kyoBenchFiber() =
        import kyo.*

        Requests.run(Requests[String](_.get(kyoUrl)))
    end kyoBenchFiber

    val zioUrl =
        import zio.http.*
        URL.decode(this.url).toOption.get
    def zioBench() =
        import zio.*
        import zio.http.*
        ZIO.service[Client]
            .flatMap(_.url(zioUrl).get(""))
            .flatMap(_.body.asString)
            .provideSome[Client](Scope.default)
            .orDie
            .asInstanceOf[UIO[String]]
    end zioBench

end GrpcBench

class GreeterImpl(size: Long) extends Greeter:

    def sayHello(request: HelloRequest): ZIO[Any, StatusException, HelloReply] =
        ZIO.succeed(HelloReply(request.request))

    def sayHelloStreaming(request: HelloRequest): ZStream[Any, StatusException, HelloReply] =
        ZStream.repeat(HelloReply(request.request)).take(size)

end GreeterImpl

