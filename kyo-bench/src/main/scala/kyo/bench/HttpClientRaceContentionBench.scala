package kyo.bench

import org.http4s.ember.client.EmberClientBuilder

class HttpClientRaceContentionBench
    extends Bench.ForkOnly("pong"):

    val concurrency = 100
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
        import cats.*
        import cats.effect.*
        import cats.implicits.*
        import cats.effect.implicits.*

        // from https://github.com/jamesward/easyracer/blob/a9aa01afefe00ab905af53a27bb2e2f005b0d00d/scala-ce3/src/main/scala/EasyRacerClient.scala#L92
        def multiRace[F[_]: Concurrent, A](fas: Seq[F[A]]): F[A] =
            def spawn[B](fa: F[B]): Resource[F, Unit] =
                Resource.make(fa.start)(_.cancel).void

            def finish(fa: F[A], d: Deferred[F, Either[Throwable, A]]): F[Unit] =
                fa.attempt.flatMap(d.complete).void

            Deferred[F, Either[Throwable, A]]
                .flatMap { result =>
                    fas
                        .traverse(fa => spawn(finish(fa, result)))
                        .use(_ => result.get.rethrow)
                }
        end multiRace

        multiRace(Seq.fill(concurrency)(catsClient.expect[String](catsUrl)))
    end catsBench

    lazy val kyoClient =
        import kyo.*
        PlatformBackend.default

    val kyoUrl =
        import sttp.client3.*
        uri"$url"

    override def kyoBenchFiber() =
        import kyo.*

        Fibers.race(Seq.fill(concurrency)(Requests.run(kyoClient)(Requests[String](_.get(kyoUrl)))))
    end kyoBenchFiber

    val zioUrl =
        import zio.http.*
        URL.decode(this.url).toOption.get

    // TODO: Initialize client once and reuse
    def zioBench() =
        import zio.*
        ZIO.succeed("pong")
    end zioBench

end HttpClientRaceContentionBench
