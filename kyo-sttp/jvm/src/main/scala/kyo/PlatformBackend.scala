package kyo

import kyo.requests.Backend
import sttp.client3._
import sttp.client3.logging.slf4j.Slf4jLoggingBackend

private[kyo] object PlatformBackend {
  val default =
    new Backend {
      val b = Slf4jLoggingBackend(HttpClientKyoBackend())
      def send[T](r: Request[T, Any]) =
        r.send(b)
    }
}
