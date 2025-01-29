package kyo.bench.arena

import org.http4s.ember.client.EmberClientBuilder

class HttpClientBench extends ArenaBench.ForkOnly("pong"):

    override lazy val zioRuntimeLayer = super.zioRuntimeLayer.merge(zio.http.Client.default)

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

    val kyoUrl =
        import sttp.client3.*
        uri"$url"

    override def kyoBenchFiber() =
        import kyo.*

        Requests(_.get(kyoUrl))
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

end HttpClientBench
