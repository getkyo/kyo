package kyo.prometheus

import cats.effect.{ExitCode, IO, IOApp}
import com.softwaremill.sttp.tapir._
import com.softwaremill.sttp.tapir.server.http4s._
import io.prometheus.client.exporter.common.TextFormat
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.server.blaze.BlazeServerBuilder

import java.io.StringWriter
import scala.concurrent.ExecutionContext.global

object PrometheusEndpoint extends IOApp {
  val metricsEndpoint: Endpoint[Unit, Unit, String, Any] =
    endpoint.get
      .in("metrics")
      .out(stringBody)
      .serverLogicSuccess(_ => IO {
        val writer = new StringWriter()
        TextFormat.write004(writer, CollectorRegistry.defaultRegistry.metricFamilySamples())
        writer.toString
      })

  val metricsRoutes: HttpRoutes[IO] = metricsEndpoint.toRoutes

  override def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO](global)
      .bindHttp(8080, "0.0.0.0")
      .withHttpApp(metricsRoutes.orNotFound)
      .serve
      .compile
      .drain
      .as(ExitCode.Success)
}
