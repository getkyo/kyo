package kyo2

import scala.util.control.NoStackTrace

case class Timeout(frame: Frame)
    extends Exception(frame.parse.position.toString)
    with NoStackTrace
