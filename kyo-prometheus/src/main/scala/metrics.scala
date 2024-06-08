package kyo.prometheus

import io.prometheus.client.{CollectorRegistry, Counter, Histogram}
import io.prometheus.client.hotspot.DefaultExports

object Metrics {
  DefaultExports.initialize()

  // Create your custom metrics
  val requestCounter: Counter = Counter.build()
    .name("http_requests_total")
    .help("Total number of HTTP requests.")
    .register()

  val requestLatency: Histogram = Histogram.build()
    .name("http_request_duration_seconds")
    .help("HTTP request latency in seconds.")
    .register()
}
