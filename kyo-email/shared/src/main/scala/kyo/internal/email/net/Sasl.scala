package kyo.internal.email.net

import kyo.*
import kyo.internal.charset.Utf8

/** The SASL initial responses the clients send, in base64 as both IMAP's `AUTHENTICATE` and SMTP's `AUTH` carry them. */
private[kyo] object Sasl:

    /** RFC 4616: an empty authorization identity, then the user and the password, separated by NUL. */
    def plain(user: String, password: Email.Password): String =
        base64(s"\u0000$user\u0000${password.value}")

    /** XOAUTH2 as Google and Microsoft define it: `user=<user>`, `auth=Bearer <token>`, each ended by `\u0001`, then a final `\u0001`. */
    def xoauth2(user: String, token: Email.OAuthToken): String =
        base64(s"user=$user\u0001auth=Bearer ${token.value}\u0001\u0001")

    def base64(text: String): String = Base64.encode(Utf8.encode(text))

end Sasl
