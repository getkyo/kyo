package kyo.bench.arena

import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder

class HttpServerContentionBench
    extends ArenaBench.ForkOnly(Seq.fill(Runtime.getRuntime().availableProcessors())("pong")):

    val concurrency = Runtime.getRuntime().availableProcessors()

    // --- Kyo server + client ---
    lazy val kyoServer: kyo.HttpServer =
        import kyo.*
        import AllowUnsafe.embrace.danger
        val handler = HttpHandler.getText("ping")(_ => "pong")
        Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(HttpServer.initUnscoped(0, "127.0.0.1")(handler))
                .flatMap(_.block(Duration.Infinity))
        ).getOrThrow
    end kyoServer

    lazy val kyoUrl =
        import kyo.*
        HttpUrl.parse(s"http://127.0.0.1:${kyoServer.port}/ping").getOrThrow

    override def kyoBenchFiber() =
        import kyo.*
        Async.fill(concurrency, concurrency)(HttpClient.getText(kyoUrl))

    // --- Cats (http4s Ember) server + client ---
    lazy val (catsServer, catsServerShutdown) =
        import cats.effect.*
        import cats.effect.unsafe.implicits.global
        import org.http4s.*
        import org.http4s.dsl.io.*
        import org.typelevel.log4cats.LoggerFactory
        import org.typelevel.log4cats.slf4j.Slf4jFactory
        import com.comcast.ip4s.*
        given CanEqual[Method, Method]     = CanEqual.derived
        given CanEqual[Uri.Path, Uri.Path] = CanEqual.derived
        given LoggerFactory[IO]            = Slf4jFactory.create[IO]
        val routes = org.http4s.HttpRoutes.of[IO] {
            case GET -> Root / "ping" => Ok("pong")
        }
        EmberServerBuilder.default[IO]
            .withHost(ip"127.0.0.1")
            .withPort(port"0")
            .withHttpApp(routes.orNotFound)
            .build
            .allocated
            .unsafeRunSync()
    end val

    lazy val catsClient =
        import cats.effect.*
        import cats.effect.unsafe.implicits.global
        import org.typelevel.log4cats.LoggerFactory
        import org.typelevel.log4cats.slf4j.Slf4jFactory
        given LoggerFactory[IO] = Slf4jFactory.create[IO]
        EmberClientBuilder.default[IO].build.allocated.unsafeRunSync()._1
    end catsClient

    lazy val catsUrl =
        import org.http4s.*
        Uri.fromString(s"http://127.0.0.1:${catsServer.address.port.value}/ping").toOption.get

    def catsBench() =
        import cats.effect.*
        import cats.implicits.*
        Seq.fill(concurrency)(catsClient.expect[String](catsUrl)).parSequence
    end catsBench

    // --- ZIO server + client ---
    override lazy val zioRuntimeLayer =
        super.zioRuntimeLayer
            .merge(zio.http.Server.defaultWith(_.binding("127.0.0.1", 0)))
            .merge(zio.http.Client.default)

    lazy val zioServerPort: Int =
        import zio.*
        import zio.http.*
        val app = Routes(
            Method.GET / "ping" -> handler(Response.text("pong"))
        )
        Unsafe.unsafe { implicit u =>
            zioRuntime.run(
                Server.install(app).asInstanceOf[UIO[Int]]
            ).getOrThrow()
        }
    end zioServerPort

    lazy val zioUrl =
        import zio.http.*
        URL.decode(s"http://127.0.0.1:$zioServerPort/ping").toOption.get

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

end HttpServerContentionBench
