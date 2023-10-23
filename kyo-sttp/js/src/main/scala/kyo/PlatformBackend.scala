package kyo

import sttp.client3._
import kyo.requests.Backend
import kyo.concurrent.fibers.Fibers

private[kyo] object PlatformBackend {
  val default =
    new Backend {
      val b = FetchBackend()
      def send[T](r: Request[T, Any]) =
        Fibers.join(r.send(b))
    }
}
