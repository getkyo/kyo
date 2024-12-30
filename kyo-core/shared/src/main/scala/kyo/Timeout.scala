package kyo

class Timeout()(using Frame) extends KyoException(t"Computation has timed out.")
