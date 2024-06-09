import io.prometheus.client.exporter.HTTPServer
import io.prometheus.client.hotspot.DefaultExports

object PrometheusEndpoint {
  def start(): Unit = {
    DefaultExports.initialize()

    // Start Prometheus HTTP server
    val server = new HTTPServer(9000)
  }
}
