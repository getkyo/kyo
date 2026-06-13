package kyo

/** Account and session state for all outbound calls. Carries the bearer token, the
  * registered phone-number id (the Cloud API path segment), the Graph API version string
  * (default "v25.0"), and the base URL (default "https://graph.facebook.com"). Immutable
  * value with fluent copy-setters; bind into scope with `WhatsApp.let`. The `apiVersion`
  * segment appears in every request URL as `baseUrl/apiVersion/phoneNumberId/messages`.
  * Fluent copy-setters shadow each field so `config.token("newToken")` returns a copy
  * rather than accessing the field.
  *
  * `toString` masks the bearer token (renders `token=***`) so the secret never leaks into
  * a log line, an exception message, or a REPL echo.
  */
final case class WhatsAppConfig(
    token: String,
    phoneNumberId: WhatsAppId.PhoneNumberId,
    apiVersion: String = "v25.0",
    baseUrl: String = "https://graph.facebook.com"
):
    def token(value: String): WhatsAppConfig                           = copy(token = value)
    def phoneNumberId(value: WhatsAppId.PhoneNumberId): WhatsAppConfig = copy(phoneNumberId = value)
    def apiVersion(value: String): WhatsAppConfig                      = copy(apiVersion = value)
    def baseUrl(value: String): WhatsAppConfig                         = copy(baseUrl = value)

    override def toString: String =
        s"WhatsAppConfig(token=***, phoneNumberId=${phoneNumberId.value}, apiVersion=$apiVersion, baseUrl=$baseUrl)"
end WhatsAppConfig
