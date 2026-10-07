package kyo.internal.email.net

import kyo.*

class SaslTest extends kyo.test.Test[Any]:

    "PLAIN's initial response: an empty authorization identity, the user and the password, NUL-separated (RFC 4616 section 4)" in {
        assert(Sasl.plain("tim", EmailLiterals.passwordOf("tanstaaftanstaaf")) == "AHRpbQB0YW5zdGFhZnRhbnN0YWFm")
    }

    "PLAIN encodes a non-ASCII password as UTF-8" in {
        assert(Sasl.plain("u", EmailLiterals.passwordOf("pé")) == Base64.encode(Span.from("\u0000u\u0000pé".getBytes("UTF-8"))))
    }

    "XOAUTH2's initial response, as Google documents it" in {
        val token = EmailLiterals.tokenOf("ya29.vF9dft4qmTc2Nvb3RlckBhdHRhdmlzdGEuY29tCg")
        assert(Sasl.xoauth2("someuser@example.com", token) ==
            "dXNlcj1zb21ldXNlckBleGFtcGxlLmNvbQFhdXRoPUJlYXJlciB5YTI5LnZGOWRmdDRxbVRjMk52YjNSbGNrQmhkSFJoZG1semRHRXVZMjl0Q2cBAQ==")
    }

    "a line of AUTH LOGIN is the UTF-8 of the text in base64" in {
        assert(Sasl.base64("user@example.com") == "dXNlckBleGFtcGxlLmNvbQ==")
    }

end SaslTest
