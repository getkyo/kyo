package kyo

case class CorsConfig(
    allowOrigin: String = "*",
    allowHeaders: Seq[String] = Seq("Content-Type", "Authorization"),
    exposeHeaders: Seq[String] = Seq.empty,
    maxAge: Int = 86400,
    allowCredentials: Boolean = false
) derives CanEqual

object CorsConfig:
    val allowAll: CorsConfig = CorsConfig()
end CorsConfig
