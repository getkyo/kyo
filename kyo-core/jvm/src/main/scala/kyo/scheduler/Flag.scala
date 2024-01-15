package kyo.scheduler

private object Flag {
  abstract class Reader[T] {
    def apply(s: String): T
  }
  object Reader {
    implicit val intReader: Reader[Int]       = Integer.parseInt(_)
    implicit val stringReader: Reader[String] = identity(_)
    implicit val longReader: Reader[Long]     = java.lang.Long.parseLong(_)
    implicit val doubleReader: Reader[Double] = java.lang.Double.parseDouble(_)
    implicit def listReader[T](implicit r: Reader[T]): Reader[List[T]] =
      (s: String) => s.split(",").toList.map(r(_))
  }
  def apply[T](name: String, default: T)(implicit r: Reader[T]) =
    Option(System.getProperty(s"kyo.scheduler.$name"))
      .map(r(_)).getOrElse(default)
}
