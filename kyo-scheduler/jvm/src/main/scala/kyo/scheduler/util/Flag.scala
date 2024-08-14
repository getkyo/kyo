package kyo.scheduler.util

private[kyo] object Flag {
    abstract class Reader[A] {
        def apply(s: String): A
    }
    object Reader {
        implicit val int: Reader[Int]         = Integer.parseInt(_)
        implicit val string: Reader[String]   = identity(_)
        implicit val long: Reader[Long]       = java.lang.Long.parseLong(_)
        implicit val double: Reader[Double]   = java.lang.Double.parseDouble(_)
        implicit val boolean: Reader[Boolean] = java.lang.Boolean.parseBoolean(_)
        implicit def list[A](implicit r: Reader[A]): Reader[List[A]] =
            (s: String) => s.split(",").toList.map(r(_))
    }
    def apply[A](name: String, default: A)(implicit r: Reader[A]) =
        Option(System.getProperty(s"kyo.scheduler.$name"))
            .map(r(_)).getOrElse(default)
}
