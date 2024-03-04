package kyo.scheduler

private object Flag:
    abstract class Reader[T]:
        def apply(s: String): T
    object Reader:
        given Reader[Int]     = Integer.parseInt(_)
        given Reader[String]  = identity(_)
        given Reader[Long]    = java.lang.Long.parseLong(_)
        given Reader[Double]  = java.lang.Double.parseDouble(_)
        given Reader[Boolean] = java.lang.Boolean.parseBoolean(_)
        given listReader[T](using r: Reader[T]): Reader[List[T]] =
            (s: String) => s.split(",").toList.map(r(_))
    end Reader
    def apply[T](name: String, default: T)(using r: Reader[T]) =
        Option(System.getProperty(s"kyo.scheduler.$name"))
            .map(r(_)).getOrElse(default)
end Flag
