package kyo

class Interrupt()(using Frame) extends KyoException("Fiber has been interrupted.")
