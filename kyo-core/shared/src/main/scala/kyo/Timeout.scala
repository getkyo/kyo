package kyo

class Timeout(fiberTrace: Maybe[IArray[Frame]] = Absent)(using Frame)
    extends KyoException(
        t"Fiber has timed out. Fiber trace dump${fiberTrace.map(_.map(_.show).mkString("\n")).map(": \n" + _).getOrElse(" unavailable.")}"
    )
