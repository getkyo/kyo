package kyo.scheduler.util

private[kyo] object Flag {
    abstract class Reader[T] {
        def apply(s: String): T
    }
    object Reader {
        implicit val int: Reader[Int]         = Integer.parseInt(_)
        implicit val string: Reader[String]   = identity(_)
        implicit val long: Reader[Long]       = java.lang.Long.parseLong(_)
        implicit val double: Reader[Double]   = java.lang.Double.parseDouble(_)
        implicit val boolean: Reader[Boolean] = java.lang.Boolean.parseBoolean(_)
        implicit def list[T](implicit r: Reader[T]): Reader[List[T]] =
            (s: String) => s.split(",").toList.map(r(_))
    }
    def apply[T](name: String, default: T)(implicit r: Reader[T]) =
        Option(System.getProperty(s"kyo.scheduler.$name"))
            .map(r(_)).getOrElse(default)
}
