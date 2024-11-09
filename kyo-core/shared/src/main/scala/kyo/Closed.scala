package kyo

import scala.util.control.NoStackTrace

case class Closed(message: String, createdAt: Frame, failedAt: Frame)
    extends Exception(s"Resource created at ${createdAt.position.show} is closed. Failure at ${failedAt.position.show}: $message")
    with NoStackTrace
