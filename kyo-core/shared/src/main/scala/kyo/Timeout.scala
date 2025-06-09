package kyo

final class Timeout(duration: Maybe[Duration] = Absent)(using Frame)
    extends KyoException("Computation has timed out" + duration.fold("")(" after " + _.show))
