package kyo.internal

import kyo.*

/** TLS certificates for tests — embedded as PEM literals to avoid ProcessBuilder on Scala Native.
  *
  * Pre-generated self-signed cert for localhost, valid 100 years.
  */
object TlsTestHelper:

    private val certPem = """-----BEGIN CERTIFICATE-----
MIIDCzCCAfOgAwIBAgIUaVFhR/MwRAG3on7dm1aKAuSlMPgwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MDQwMzA0Mjg0MFoYDzIxMjYw
MzEwMDQyODQwWjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQCkTAeZ1rQQoosZh15OJj93hZ1hLuZCeGJX77zt2QKR
aG4IGhXHG26Cp9VCo0+FZUxxXdBfTjPJ8m6zvVjpqST9Rznll+nIhNN+Wol8k2Af
4RPOVpsfW8EiuIplI6GibGEHUG8o12eiTyJu3I2ihvQ0wDUrMz+QlFXWN1aOaEOk
3c2GoB4n8jGLA3NIb+lS89T0TRjABN1UIf0lddI4xxngHrBOu+KIhvBFbyk225md
o9pYYnUiS7li71ozHROuwn4NDHYOKH69dnxqFJJrtD5EBHPG3Cgbp8krRAaVcsOV
7n5Efr1PhPW3RBtu5ZIepXWB+PgmFQjHVjtYoR/5D8ABAgMBAAGjUzBRMB0GA1Ud
DgQWBBRsHOWBv/nFaykie0kYyb3dqHN9ZjAfBgNVHSMEGDAWgBRsHOWBv/nFayki
e0kYyb3dqHN9ZjAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBx
muYE6zZnj3HoAYkx/rXHe9LsJv4CViIvtFuL1RvXKATe4qX4ZHGqfT2F0rIWT3zo
Xu1hw6ZZarnI616QBM22OxXN7V9crguXjjyZxYmu1rBFTCorLcxwjnRyADdRgAnY
L0EkUFC6vJZSasbuKodoDw40h7jV8thZpX6EIO0mVRMxH/JWW25FRpwuZVJuYB3x
ZbZPly1rRWq83H0K4TwRFCwS1443xqe5utKprcrcTLNbvcQ9K1xwRUBHF+rKbx84
rxenpAdlMNUX7MtOfDCzGj8UnveDCVAhuIwxcSoUuZ1FTh696IP2Teso8XWO1xqq
acWGmcG78K0jVPd3Mzpv
-----END CERTIFICATE-----"""

    private val keyPem = """-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCkTAeZ1rQQoosZ
h15OJj93hZ1hLuZCeGJX77zt2QKRaG4IGhXHG26Cp9VCo0+FZUxxXdBfTjPJ8m6z
vVjpqST9Rznll+nIhNN+Wol8k2Af4RPOVpsfW8EiuIplI6GibGEHUG8o12eiTyJu
3I2ihvQ0wDUrMz+QlFXWN1aOaEOk3c2GoB4n8jGLA3NIb+lS89T0TRjABN1UIf0l
ddI4xxngHrBOu+KIhvBFbyk225mdo9pYYnUiS7li71ozHROuwn4NDHYOKH69dnxq
FJJrtD5EBHPG3Cgbp8krRAaVcsOV7n5Efr1PhPW3RBtu5ZIepXWB+PgmFQjHVjtY
oR/5D8ABAgMBAAECggEAB2/TlkCUNP6OilDI0YPEsq3PImODk7WsSeu1oxSEG73c
4SwBGVqiBXMAYbxPsKuVqroC4RWCnZ0GKsei2yzHWlmbgst+DxAIHLuPZp6rJrZC
9b+Q1PoLw30T8qEKw3XhbHnVFkWt0LiBSABevuEZ87tRjMuMkIhOXFxz5A1PJoq6
+6qeggpMqF7W+UpEmiLe9jHhubO//209wed+tU3hZjkUzOPFzBD4EdgeoDev1Bne
9CzFM7m68QzxAYlbBOOIpofYmePNAL8FBNfE+kAjMhq76RnL1ZkTQ/8OO4n/ofJm
ArD0XDkdTY+ayhhv3e0A/jw+FiWma2Vnn5Q+xU6EOQKBgQDnaMzWYokkWnmG3hXc
OM8qJzcTzIcKf5CYBUD0/aDRd7pBsNmpj7rCrnfF93ScRmQ8yaUZW1ik0xORdV95
wmaecxw4XvSC6hWo9WqO1G48HXWWmHAx+X4JfXuObI0DMmzm6B2CoevzGiNOOumL
gYbzcQUFmDoGOzVBHfUf7bmHpQKBgQC1wYWZF5/2ZH1hd1GPazi5CNEpRmHCgUVO
71czX24FbrGUX8llYrRDGAHVC1aW1psK2UfrPwmh73FJ7f4afBT6IdA2oX2EmjRc
NIVEWuYYDJa3OZRPluM2deDJDhUSEtDb1GwDC1yOkoWF54yIi7LF9hgfDLwcIWUF
XOZknnLILQKBgA85vgB9CzjxGv3cruOGeVr0qZML6fau3fyim9uCtIoTrpWT5T5a
zpbwk8DavzlpCD9XpR09/V8a5Da12kpQmB/kwv6SgNP8QuCTBSfQolAiQBJghUFE
gR5uuypryftj3fZzXz2xGa0tExWamrMrGo356bKsiWTPkHlwVyh6o7JhAoGARxYJ
SncS+SsUXqpsG8uzw6rPI3WDhq9IjDbPxCfuv/ErQvyzqBOSrSsWjFyC4TvOJ9AT
cM6W1d7wUBnk6DbffXT8GmHO1a36DJX+CV4D4CQMTl0WxIofE43G/NulIgx544CR
Ph+Tc6+ayWRmcoEwynwVsw8oA1iMSiyrb28JNYECgYA/6oVFnSh6YsjO+UIKnkkB
Yqx/cLCRhSGU5TJp9y0bgp9wI16SfVZ7sbFL8Q/JHb3DVyc3B+Ob8gffY5xFZsKk
G97a4B6FbMcb17nLrkt9+RtD1yWt+tlFOVpz9OMXHiZAE2b0eCSx3cafONHN9nrN
nExc1OKAJ9rzBEd8BEySPQ==
-----END PRIVATE KEY-----"""

    lazy val (certPath, keyPath): (String, String) =
        val certFile = java.io.File.createTempFile("kyo-tls-cert", ".pem")
        val keyFile  = java.io.File.createTempFile("kyo-tls-key", ".pem")
        certFile.deleteOnExit()
        keyFile.deleteOnExit()
        val cw = new java.io.FileWriter(certFile)
        cw.write(certPem)
        cw.close()
        val kw = new java.io.FileWriter(keyFile)
        kw.write(keyPem)
        kw.close()
        (certFile.getAbsolutePath, keyFile.getAbsolutePath)
    end val

    /** Server TLS config with self-signed cert for localhost. */
    lazy val serverTlsConfig: TlsConfig = TlsConfig(
        certChainPath = Present(certPath),
        privateKeyPath = Present(keyPath)
    )

    /** Client TLS config that trusts any certificate (for connecting to self-signed test servers). */
    lazy val clientTlsConfig: TlsConfig = TlsConfig(
        trustAll = true
    )

end TlsTestHelper
