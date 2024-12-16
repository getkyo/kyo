package kyo

import scala.util.control.NoStackTrace

class Timeout()(using Frame) extends KyoException(t"Computation has timed out.")
