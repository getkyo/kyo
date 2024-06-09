import io.prometheus.client.{Counter, Gauge, Summary}

object Metrics {
  val requestCount: Counter = Counter.build()
    .name("request_count")
    .help("Total request count")
    .register()

  val inProgressRequests: Gauge = Gauge.build()
    .name("in_progress_requests")
    .help("In progress requests")
    .register()

  val requestDuration: Summary = Summary.build()
    .name("request_duration_seconds")
    .help("Time spent processing request")
    .register()

  def processRequest(): Unit = {
    inProgressRequests.inc()
    val start = System.nanoTime()
    Thread.sleep(scala.util.Random.nextInt(1000))
    val duration = (System.nanoTime() - start) / 1e9d
    requestDuration.observe(duration)
    inProgressRequests.dec()
    requestCount.inc()
  }
}
