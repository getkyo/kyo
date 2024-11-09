package kyo

import scala.util.control.NoStackTrace

case class Timeout(frame: Frame)
    extends Exception(frame.position.show)
    with NoStackTrace
