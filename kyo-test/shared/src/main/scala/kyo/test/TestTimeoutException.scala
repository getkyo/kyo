package kyo.test

final case class TestTimeoutException(message: String) extends Throwable(message, null, true, false)
