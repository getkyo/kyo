import sttp.tapir._
import sttp.tapir.server.netty.NettyFutureServerInterpreter
import sttp.tapir.prometheus.PrometheusMetrics

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

object main extends App {
  // Start Prometheus endpoint
  PrometheusEndpoint.start()

  // Define metrics endpoint
  val prometheusMetrics = PrometheusMetrics.default[Future]()
  val metricsEndpoint = endpoint.get.in(prometheusMetrics.path).serverLogicSuccess(_ => Future.successful(prometheusMetrics.registry))

  val bindAndCheck = for {
    binding <- NettyFutureServerInterpreter().toServer(metricsEndpoint).flatMap(_.start())
  } yield binding

  bindAndCheck.onComplete { result =>
    println(s"Prometheus metrics server started: $result")
  }

  while (true) {
    Metrics.processRequest()
  }
}
