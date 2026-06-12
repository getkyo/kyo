package kyo.net.internal.tls

/** A fixed self-signed test certificate whose identity is `evil.example.com` (CN=evil.example.com, SAN dns:evil.example.com), valid 100
  * years, embedded as PEM literals so the cross-platform TLS verification tests need no `openssl` process (Scala Native cannot fork one).
  *
  * This cert deliberately does NOT match the `localhost` identity that [[TlsTestCert]] carries and that a client connecting to `localhost`
  * expects. It is the negative-control peer for the hostname-verification tests: a verifying client that pins this cert as its own CA (so the
  * chain validates) but expects to be talking to `localhost` MUST still reject the handshake on the host-name mismatch (RFC 6125 §6). The cert
  * is its own issuer (self-signed), so pinning it via `caCertPath` makes the chain check pass and isolates the host-name check as the only
  * thing that can fail.
  */
object TlsWrongNameCert:

    val certPem: String =
        """-----BEGIN CERTIFICATE-----
MIIDNjCCAh6gAwIBAgIUJ3Ske++iv0tozG8J9vsJSr2MHyQwDQYJKoZIhvcNAQEL
BQAwGzEZMBcGA1UEAwwQZXZpbC5leGFtcGxlLmNvbTAgFw0yNjA2MDcxMjAzNTha
GA8yMTI2MDUxNDEyMDM1OFowGzEZMBcGA1UEAwwQZXZpbC5leGFtcGxlLmNvbTCC
ASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAOEXq+koh7JcVZKepqYw+Pcp
bEn/1iPBZOXy6t5KngiBS89phTlvVTOb5DFAGvfBsfDFs7ZLXE3v04fiM0+Ome4b
cj+Mza423A9jBXOpF8PSBTWbsCkuiLZWl4f06rYpmaPkv2sNc2mEklJumxi18BeF
nCJrZCidRTrqrm3BwSp0FCoaCQefCF8FhjIQPsXufDGX/MM8+htAN19Ugg7vuVvu
qUVLMkezEC+VUh8immzQx5/2vffCEyGYFJxrKyUgPUlIF0TuzF5Kt4evhAmQI0nk
Dh+1k4PKaP7UK0XtXLlJI/Wtu41tqSNRhRMhipBn/7sRSNXR2+maMy3GKlUZziEC
AwEAAaNwMG4wHQYDVR0OBBYEFOO6s1pYbSEMazvzuEZetaXYR4XKMB8GA1UdIwQY
MBaAFOO6s1pYbSEMazvzuEZetaXYR4XKMA8GA1UdEwEB/wQFMAMBAf8wGwYDVR0R
BBQwEoIQZXZpbC5leGFtcGxlLmNvbTANBgkqhkiG9w0BAQsFAAOCAQEArn/1o5of
Z5vcm0CpnVQNqXX1sODxmqgNywpBsnaa/w5mXPL+NAh7cFvrMiufSWh3tX/2VAWK
DuQxEw2Qpc4SMHNuD2HadfKhuT+waHSIfHPV3jmjaDpS53gtFyGEvDylJY4cWc/+
UBudCzCX34/yA7+0PXNF7qLiZegGvVRzWNlg/Qc/t0AsrqEOiP45BSvSQWJQ1xz6
iWDUXfrSIl80fGaLWmJnWsQ3+Tayczz0dIiuAs+QOezuqiXrFmL6/6vKIYrLlqL+
aiZllNgOaUbL67LNBQRLZ/POeYfEAN3p8NsuBRT8EPzJqCuKM2k8Ptauw82q2uH9
vuQyI/dbiP1oug==
-----END CERTIFICATE-----
"""

    val keyPem: String =
        """-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQDhF6vpKIeyXFWS
nqamMPj3KWxJ/9YjwWTl8ureSp4IgUvPaYU5b1Uzm+QxQBr3wbHwxbO2S1xN79OH
4jNPjpnuG3I/jM2uNtwPYwVzqRfD0gU1m7ApLoi2VpeH9Oq2KZmj5L9rDXNphJJS
bpsYtfAXhZwia2QonUU66q5twcEqdBQqGgkHnwhfBYYyED7F7nwxl/zDPPobQDdf
VIIO77lb7qlFSzJHsxAvlVIfIpps0Mef9r33whMhmBScayslID1JSBdE7sxeSreH
r4QJkCNJ5A4ftZODymj+1CtF7Vy5SSP1rbuNbakjUYUTIYqQZ/+7EUjV0dvpmjMt
xipVGc4hAgMBAAECggEABrJ42wgpmMEJCxf9KYWG/s7vdHiFc00PgWJRInnctQWT
JHHFX5BZsV5Sc4muqQ+iXvbO554F5MwS1+CUehSU2Ayz1pgL+EKUbmvEj4H+MvAW
ghdmriRUN9KgkbsT2bAoAgRDaBNqIJSryC1CIEHXidJKneE38Be/0Hvc4pHze0ho
aiojNawb8ppl9I6aDarWtuffeSeLwFLZxsxgJpieRaBj2UQRKg7t6JgQbMBnrm9v
J4NVbxCCKpfQp1bp0K0O1BYfZhKtBhqYvPCibAvPa/DM7em3jxNO8s19NNDCuuSQ
aMoqmVT23WbVfhkvjZQvhRY11+o/4g2TmSTY9N2y8QKBgQD3yMfoBO+JJxzO0v3B
INOstu/7bx3qkwOBaDgCbS343VAl1oAD+L7uP0HQEhrk2s+9XYtrhW4s9AgaDDW0
KxfMKsSj3k7Vwegngllk6sXNCzOd3SIA09h7aelAy+kuwBY0IPm15PUYc8ASwZy/
SxxiALWtMqrOv+GJTkqVYr6KCQKBgQDojkes3iVNCeQdEgrqlA/72uaJL5O7qtyg
viUz+yZ6UnEGhmG+gu5nbj7fGnRpzjTHDA6H0URo4iaqI+jcJ6lB+poOXDt+YVr6
xliUViFlUirOA+OmtsoXDdSxvgRIc5YKjmC2ex0bwkiKhF6JX5C54t+mFIHaN2nT
zSdClFWJWQKBgBqYKCyU6wizc/oW1zl1RoLRF6zK3lEg+k1XXDuWcEq3pjSJcy/5
8LQtgejNKKnLemR+t8oQhiS5BG2XReRSg7lcFcLox1lV+I7VBLc6I4TAYQfehhnE
owWL9ocH270yzK9HosWND2lScxkQQrydWSyDmvw95etO2OwdxQ+Hi5/RAoGBAOTO
F/cdGdYSRT3U4qipxJAnb2rKRLAAC9KbQj9Cezkeo9WnocOvTqC092bKwH7ZQ1QT
qLg4TPZki2YarqDs5LrltW0rkd1mK/1P6RcJJxJpJXRMn08HyQ2lrf6y1cPy9Uyt
iLoBp0IIhRbD5b+DJwG9Fg5xXtE0dArPbjEzgHUBAoGBAMzo2b0047+e8E5/X15A
MFkausOblJhgSa28hSryIy5SJY+Nl/K1rYTZD9LboypVQKQd0oNjnSMwNKg6IVfy
TCC3ERoU2jd3u7YaMiMKBpOd3CauRnj7LMTyV0gz3k7jXXMkSu9ES45w1seFeITw
CweH1QCptq1Kv4KmVWaQDlzK
-----END PRIVATE KEY-----
"""

    /** Write the embedded cert and key to fresh temp files and return their absolute paths (used by `NetTlsConfig.certChainPath` /
      * `privateKeyPath` / `caCertPath`). The files are marked delete-on-exit.
      */
    lazy val (certPath, keyPath): (String, String) =
        val certFile = java.io.File.createTempFile("kyo-net-tls-wrongname-cert", ".pem")
        val keyFile  = java.io.File.createTempFile("kyo-net-tls-wrongname-key", ".pem")
        certFile.deleteOnExit()
        keyFile.deleteOnExit()
        val cw = new java.io.FileWriter(certFile)
        try cw.write(certPem)
        finally cw.close()
        val kw = new java.io.FileWriter(keyFile)
        try kw.write(keyPem)
        finally kw.close()
        (certFile.getAbsolutePath, keyFile.getAbsolutePath)
    end val

end TlsWrongNameCert
