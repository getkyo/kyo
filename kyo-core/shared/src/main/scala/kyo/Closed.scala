package kyo

import scala.util.control.NoStackTrace

case class Closed(message: String, createdAt: Frame, failedAt: Frame)
    extends Exception(s"Resource created at ${createdAt.parse.position} is closed. Failure at ${failedAt.parse.position}: $message")
    with NoStackTrace
