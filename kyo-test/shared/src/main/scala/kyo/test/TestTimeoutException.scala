package kyo.test

import zio.stacktracer.TracingImplicits.disableAutoTrace

final case class TestTimeoutException(message: String) extends Throwable(message, null, true, false)
