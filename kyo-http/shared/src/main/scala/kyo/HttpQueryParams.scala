package kyo

/** Query parameters for HTTP requests.
  *
  * An ordered, multi-valued collection of name-value string pairs representing URL query parameters. Backed by a `Seq[(String, String)]` —
  * zero allocation when empty (`Nil`). Parameters preserve insertion order and allow duplicate keys (`?tag=a&tag=b`).
  *
  * @see
  *   [[kyo.HttpClient]] Convenience methods accept HttpQueryParams
  * @see
  *   [[kyo.HttpUrl]] Carries parsed query parameters
  */
opaque type HttpQueryParams = Seq[(String, String)]

object HttpQueryParams:
    val empty: HttpQueryParams = Nil

    def init(name: String, value: String): HttpQueryParams = Seq((name, value))
    def init(params: (String, String)*): HttpQueryParams   = params.toSeq

    extension (self: HttpQueryParams)
        def add(name: String, value: String): HttpQueryParams = self :+ (name, value)
        def get(name: String): Maybe[String]                  =
            @scala.annotation.tailrec
            def loop(remaining: Seq[(String, String)]): Maybe[String] =
                remaining match
                    case (n, v) +: _ if n == name => Present(v)
                    case _ +: tail                => loop(tail)
                    case _                        => Absent
            loop(self)
        end get
        def getAll(name: String): Seq[String]          = self.collect { case (n, v) if n == name => v }
        def isEmpty: Boolean                           = self.isEmpty
        def nonEmpty: Boolean                          = self.nonEmpty
        def size: Int                                  = self.size
        def toSeq: Seq[(String, String)]               = self
        def foreach(f: (String, String) => Unit): Unit = self.foreach(f.tupled)

        /** Encodes to URL query string format: "key1=val1&key2=val2" with percent-encoding. */
        def toQueryString: String =
            if self.isEmpty then ""
            else
                self.map((k, v) => s"${encode(k)}=${encode(v)}").mkString("&")
    end extension

    private def encode(s: String): String = internal.PercentEncoding.encode(s, internal.PercentEncoding.Mode.Component)

end HttpQueryParams
