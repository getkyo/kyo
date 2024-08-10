// package kyo.bench

// import org.http4s.ember.client.EmberClientBuilder

// class HttpClientBench extends Bench.ForkOnly("pong"):

//     override val zioRuntimeLayer = super.zioRuntimeLayer.merge(zio.http.Client.default)

//     val url = TestHttpServer.start(1)

//     lazy val catsClient =
//         import cats.effect.*
//         import cats.effect.unsafe.implicits.global
//         EmberClientBuilder.default[IO].build.allocated.unsafeRunSync()._1
//     end catsClient

//     val catsUrl =
//         import org.http4s.*
//         Uri.fromString(url).toOption.get

//     def catsBench() =
//         import cats.effect.*

//         catsClient.expect[String](catsUrl)
//     end catsBench

//     lazy val kyoClient =
//         import kyo.*
//         PlatformBackend.default

//     val kyoUrl =
//         import sttp.client3.*
//         uri"$url"

//     override def kyoBenchFiber() =
//         import kyo.*

//         Abort.run(Requests(_.get(kyoUrl))).map(_.getOrThrow)
//     end kyoBenchFiber

//     val zioUrl =
//         import zio.http.*
//         URL.decode(this.url).toOption.get
//     def zioBench() =
//         import zio.*
//         import zio.http.*
//         ZIO.service[Client]
//             .flatMap(_.url(zioUrl).get(""))
//             .flatMap(_.body.asString)
//             .provideSome[Client](Scope.default)
//             .orDie
//             .asInstanceOf[UIO[String]]
//     end zioBench

// end HttpClientBench
