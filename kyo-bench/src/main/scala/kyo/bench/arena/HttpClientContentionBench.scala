package kyo.bench.arena

import org.http4s.ember.client.EmberClientBuilder

class HttpClientContentionBench
    extends ArenaBench.ForkOnly(Seq.fill(Runtime.getRuntime().availableProcessors())("pong")):
    override val zioRuntimeLayer = super.zioRuntimeLayer.merge(zio.http.Client.default)

    val concurrency = Runtime.getRuntime().availableProcessors()
    val url         = TestHttpServer.start(concurrency)

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
        import cats.implicits.*

        Seq.fill(concurrency)(catsClient.expect[String](catsUrl)).parSequence
    end catsBench

    lazy val kyoClient =
        import kyo.*
        PlatformBackend.default

    val kyoUrl =
        import sttp.client3.*
        uri"$url"

    override def kyoBenchFiber() =
        import kyo.*

        Async.repeat(concurrency, concurrency)(Requests(_.get(kyoUrl)))
    end kyoBenchFiber

    val zioUrl =
        import zio.http.*
        URL.decode(this.url).toOption.get

    def zioBench() =
        import zio.*
        import zio.http.*

        val request =
            ZIO.service[Client]
                .flatMap(_.url(zioUrl).get(""))
                .flatMap(_.body.asString)
                .provideSome[Client](Scope.default)
                .asInstanceOf[Task[String]]

        ZIO.collectAll(Seq.fill(concurrency)(request.forkDaemon)).flatMap(ZIO.foreach(_)(_.join)).orDie
    end zioBench

end HttpClientContentionBench
