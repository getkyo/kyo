package kyo

case class HttpCors(
    allowOrigin: String = "*",
    allowHeaders: Seq[String] = Seq("Content-Type", "Authorization"),
    exposeHeaders: Seq[String] = Seq.empty,
    maxAge: Int = 86400,
    allowCredentials: Boolean = false
) derives CanEqual

object HttpCors:
    val allowAll: HttpCors = HttpCors()
end HttpCors
