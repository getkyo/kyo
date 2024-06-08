package kyo.prometheus

import cats.effect.{ExitCode, IO, IOApp}
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.ExecutionContext.global

object Main extends IOApp {
  val allRoutes: HttpRoutes[IO] = Router(
    "/" -> PrometheusEndpoint.metricsRoutes
  )

  // Create the http4s server
  override def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO](global)
      .bindHttp(8080, "0.0.0.0")
      .withHttpApp(allRoutes.orNotFound)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
}
