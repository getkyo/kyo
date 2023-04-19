package kyo

import sttp.client3.FetchBackend

private[kyo] object PlatformBackend {
  val instance = FetchBackend()
}
