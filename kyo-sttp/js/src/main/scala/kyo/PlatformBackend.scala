package kyo

import sttp.client3._
import kyo.requests.Backend

object PlatformBackend {
  val default =
    new Backend {
      val b = FetchBackend()
      def send[T](r: Request[T, Any]) =
        Fibers.fromFuture(r.send(b))
    }
}
