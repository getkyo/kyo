package kyo.bench.arena

import kyo.discard
import org.http4s.ember.client.EmberClientBuilder
import org.openjdk.jmh.annotations.*

class HttpServerContentionBench
    extends ArenaBench.ForkOnly(Seq.fill(Runtime.getRuntime().availableProcessors())("pong")):

    inline given [A]: CanEqual[A, A] = CanEqual.derived

    override lazy val zioRuntimeLayer = super.zioRuntimeLayer.merge(zio.http.Client.default)

    val concurrency = Runtime.getRuntime().availableProcessors()

    @volatile private var kyoStarted  = false
    @volatile private var zioStarted  = false
    @volatile private var catsStarted = false

    // --- Kyo server ---

    lazy val kyoSrv =
        import kyo.*
        import AllowUnsafe.embrace.danger
        val h = HttpHandler.get("/ping")(_ => HttpResponse.ok("pong"))
        val srv = Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(
                HttpServer.initUnscoped(HttpServer.Config(port = 9004))(h)
            ).flatMap(_.block(Duration.Infinity))
        ).getOrThrow
        kyoStarted = true
        srv
    end kyoSrv

    lazy val kyoUrl =
        import sttp.client3.*
        val u = s"http://localhost:${kyoSrv.port}/ping"
        uri"$u"
    end kyoUrl

    override def kyoBenchFiber() =
        import kyo.*
        Async.fill(concurrency, concurrency)(Requests(_.get(kyoUrl)))
    end kyoBenchFiber

    @Benchmark
    def forkKyoHttp(warmup: WarmupJITProfile.KyoForkWarmup): kyo.Chunk[String] =
        import kyo.*
        import AllowUnsafe.embrace.danger
        val url = s"http://localhost:${kyoSrv.port}/ping"
        Sync.Unsafe.evalOrThrow(
            Fiber.initUnscoped(
                Async.fill(concurrency, concurrency)(HttpClient.send(HttpRequest.get(url)).map(_.bodyText))
            ).flatMap(_.block(Duration.Infinity))
        ).getOrThrow
    end forkKyoHttp

    // --- ZIO server ---

    lazy val zioFiber =
        import zio.*
        import zio.http.*
        val app = Routes(Method.GET / "ping" -> handler(Response.text("pong")))
        val fiber = Unsafe.unsafe { implicit u =>
            zio.Runtime.default.unsafe.fork(
                Server.serve(app).provide(Server.defaultWithPort(9005))
            )
        }
        zioStarted = true
        fiber
    end zioFiber

    lazy val zioUrl =
        import zio.http.*
        discard(zioFiber)
        URL.decode("http://localhost:9005/ping").toOption.get
    end zioUrl

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

    // --- Cats server ---

    lazy val (catsSrv, catsClose) =
        import cats.effect.*
        import cats.effect.unsafe.implicits.global
        import org.http4s.*
        import org.http4s.dsl.io.*
        import org.http4s.ember.server.EmberServerBuilder
        import com.comcast.ip4s.*
        import org.typelevel.log4cats.LoggerFactory
        import org.typelevel.log4cats.slf4j.Slf4jFactory
        given LoggerFactory[IO] = Slf4jFactory.create[IO]
        val routes = HttpRoutes.of[IO] {
            case GET -> Root / "ping" => Ok("pong")
        }
        val result = EmberServerBuilder.default[IO]
            .withHost(host"0.0.0.0")
            .withPort(Port.fromInt(9006).get)
            .withHttpApp(routes.orNotFound)
            .withShutdownTimeout(scala.concurrent.duration.Duration.Zero)
            .build
            .allocated
            .unsafeRunSync()
        catsStarted = true
        result
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
        discard(catsSrv)
        Uri.fromString("http://localhost:9006/ping").toOption.get
    end catsUrl

    def catsBench() =
        import cats.effect.*
        import cats.implicits.*
        Seq.fill(concurrency)(catsClient.expect[String](catsUrl)).parSequence
    end catsBench

    // --- Teardown ---

    @TearDown(Level.Trial)
    def tearDownServers(): Unit =
        if kyoStarted then
            try
                import kyo.*
                import AllowUnsafe.embrace.danger
                discard(Sync.Unsafe.evalOrThrow(
                    Fiber.initUnscoped(kyoSrv.closeNow).flatMap(_.block(Duration.Infinity))
                ))
            catch case _: Throwable => ()
        end if
        if catsStarted then
            try
                import cats.effect.unsafe.implicits.global
                catsClose.unsafeRunSync()
            catch case _: Throwable => ()
        end if
        if zioStarted then
            try
                discard(zio.Unsafe.unsafe { implicit u =>
                    zio.Runtime.default.unsafe.run(zioFiber.interrupt)
                })
            catch case _: Throwable => ()
        end if
    end tearDownServers

end HttpServerContentionBench
