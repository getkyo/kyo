package kyo.bench.arena

import org.http4s.ember.client.EmberClientBuilder
import org.openjdk.jmh.annotations.*

class HttpClientContentionBench
    extends ArenaBench.ForkOnly(Seq.fill(Runtime.getRuntime().availableProcessors())("pong")):
    override val zioRuntimeLayer = super.zioRuntimeLayer.merge(zio.http.Client.default)

    val concurrency = Runtime.getRuntime().availableProcessors()
    val url         = TestHttpServer.start(concurrency)

    lazy val catsClient =
        import cats.effect.*
        import cats.effect.unsafe.implicits.global
        import org.typelevel.log4cats.LoggerFactory
        import org.typelevel.log4cats.slf4j.Slf4jFactory
        given LoggerFactory[IO] = Slf4jFactory.create[IO]
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

    val kyoUrl =
        import sttp.client3.*
        uri"$url"

    override def kyoBenchFiber() =
        import kyo.*

        Async.fill(concurrency, concurrency)(Requests(_.get(kyoUrl)))
    end kyoBenchFiber

    @Benchmark
    def forkKyoHttp(warmup: WarmupJITProfile.KyoForkWarmup): kyo.Chunk[String] =
        import kyo.*
        import AllowUnsafe.embrace.danger
        Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(
                Async.fill(concurrency, concurrency)(HttpClient.getText(url))
            ).flatMap(_.block(Duration.Infinity))
        ).getOrThrow
    end forkKyoHttp

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
