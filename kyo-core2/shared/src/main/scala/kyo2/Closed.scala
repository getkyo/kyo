package kyo2

import scala.util.control.NoStackTrace

case class Closed(resource: String, createdAt: Frame, failedAt: Frame)
    extends Exception(s"$resource created at ${createdAt.parse.position} is closed. Failure at ${failedAt.parse.position}")
    with NoStackTrace
