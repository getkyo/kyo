package kyo

import kyo.requests.Backend
import sttp.client3._

private[kyo] object PlatformBackend {
  val default =
    new Backend {
      val b = HttpClientKyoBackend()
      def send[T](r: Request[T, Any]) =
        r.send(b)
    }
}
