package kyo

import sttp.client3.HttpClientFutureBackend

private[kyo] object PlatformBackend {
  val instance = HttpClientFutureBackend()
}
