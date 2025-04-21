package kyo

class Timeout(waiting: Maybe[Frame])(using Frame)
    extends KyoException(t"Computation has timed out.", waiting.fold("")(frame => "Waiting for:\n" + frame.render))
