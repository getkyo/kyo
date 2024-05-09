package kyo.bench

import org.http4s.ember.client.EmberClientBuilder

class HttpClientBench extends Bench.ForkOnly("pong"):

    val port = 9999
    val url  = TestHttpServer.start(port)

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

    // TODO: Initialize client once and reuse
    def zioBench() =
        import zio.*
        // import zio.http.*

        // ZIO.service[Client].flatMap(_.url(zioUrl).get("")).flatMap(_.body.asString).provide(Client.default, Scope.default).orDie
        ZIO.succeed("pong")
    end zioBench

end HttpClientBench
