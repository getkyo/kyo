package kyo.net

import kyo.*

/** A fixed self-signed test certificate (CN=localhost, SAN dns:localhost / ip:127.0.0.1, valid 100 years), embedded as PEM literals and written
  * to a temp file through the cross-platform `kyo.Path`, so the SAME certificate fixture is usable from every backend's tests (JVM, Native, JS)
  * without `keytool` / `openssl` (Scala Native cannot fork one) and without a platform-specific temp-file API (`java.io.File` is absent on JS).
  *
  * This is the canonical copy. The JVM/Native `TlsTestCert` re-exports these PEM literals and the golden hash; backend-specific TLS suites that
  * only need a cert on disk use [[writePems]].
  *
  * `certGoldenSha256` is the precomputed SHA-256 of this certificate's leaf DER (RFC 5929 tls-server-end-point), the channel-binding golden the
  * cert-binding tests assert against.
  */
object TlsTestCertShared:

    val certPem: String =
        """-----BEGIN CERTIFICATE-----
MIIDJzCCAg+gAwIBAgIUAsK6xZSOkkUp0XUzT5nHid5YS6owDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MDYwMjExNTc1MloYDzIxMjYw
NTA5MTE1NzUyWjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQC7AWd9yEu3xXwOF/K4ie3+PzmJhWGxosx/zoBLbNR7
YtZFd784fO4uAJ8yOpqPctUouEj616P+fjkTWSfEIRkhAafpjv8N/wPZa4dX745w
cBa5UU85iOzujVToAJxLDN9MNrsEXp07WIYumn+iU9AKwrNSIkkR7/DaPh27pZmk
y1P5HANIx9N3zf31dVVJ3K2+RBO/VGqAVMHzahLEpZkC7Zqr9QigWJZVwF0dyC+D
UnVqWPDFVazdm0xdIFJWQn8pCzWSqd2OSLLZYB+h9cLnuvg+J0DjN2u9QrMA+qt1
EemLzVlAnGuc5YzSBsRgRQZ+T/Tzq7GuSTvaZU+5p1vrAgMBAAGjbzBtMB0GA1Ud
DgQWBBRUGfq/Wl7WS+0uP9S9V8Cf+q7b5DAfBgNVHSMEGDAWgBRUGfq/Wl7WS+0u
P9S9V8Cf+q7b5DAPBgNVHRMBAf8EBTADAQH/MBoGA1UdEQQTMBGCCWxvY2FsaG9z
dIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEAfvzIzdIDy2CUodiRv1hb3h11YPrF
9me1zwf+uDFYugH6/xQtdXylwKdo9PkcHQysNkZyaV0hTp3Oe9DS14P3Qka66A1p
3KTarUm/bubad6myhYGz9heq20NObI4EO7TCVnGoOrkU4DsX2kuiEeACh2g4zubB
5W9q5f5TvcFwzTWTs3LHoBC0IRiBJzu6ZJF+lhgbQq1XEVnyMNrBceAVhmIiRDEj
EJ+GHXzdYD5hdS3GOgjhwL/jv+2tJluCEtAQP1jveJC82MaxzhDAokj8jpNJwJIb
EyIgKV+rx7jVsRfunsZgA2DFUCzQsp7nAylV6r58itUePQ268JKMkEZtsA==
-----END CERTIFICATE-----
"""

    val keyPem: String =
        """-----BEGIN PRIVATE KEY-----
MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQC7AWd9yEu3xXwO
F/K4ie3+PzmJhWGxosx/zoBLbNR7YtZFd784fO4uAJ8yOpqPctUouEj616P+fjkT
WSfEIRkhAafpjv8N/wPZa4dX745wcBa5UU85iOzujVToAJxLDN9MNrsEXp07WIYu
mn+iU9AKwrNSIkkR7/DaPh27pZmky1P5HANIx9N3zf31dVVJ3K2+RBO/VGqAVMHz
ahLEpZkC7Zqr9QigWJZVwF0dyC+DUnVqWPDFVazdm0xdIFJWQn8pCzWSqd2OSLLZ
YB+h9cLnuvg+J0DjN2u9QrMA+qt1EemLzVlAnGuc5YzSBsRgRQZ+T/Tzq7GuSTva
ZU+5p1vrAgMBAAECggEABGd+j/xRKDVa/Bv9R/JbrBLCIKaHC/95EIOFCwG3qWZF
BKLS2po6o9O47B5sOHesZIaelWXRw3MmlfmSEbDz3g6jbUFEaYh5hzvclqoaMTS6
nEe5dXHvnpiuiL5G8A+QDMP3OJ2f11943Y0e92xA6Jf4UDVlgiokAofW/G3khfiH
mXwLenyK2QLIXfXBNdVwBEC1zqUktmN0OVM6cnPL28tFNEk9mkS3ILsyP95ucpXe
u6Jlb4yGjCIoEdvKGXGgp7lsx4VYF8jorO1s8sQX0GQlf51WRllWLEs3YtFmoetB
UusnX4n43qO7yMV0+CLXXzTLla9/l8w5hotWN9zv/QKBgQDxCIA8tF8mz2y/OovF
Cbkx/m0LJkuBAvNSgSDLpIJk4uFXGSlBgcSFiHCns4TxtpQ54veUkcPnnDL9o4sv
43dXrztfQT/J/7GmttPuG38TRQa7ax0MOy8KFxeKuE8JGCvyP1ZRd0+JiozGebCH
ZTBJx3mfpcr9W/lq6RdpMlzQFwKBgQDGnhIe5K4c7xieuJH2fJGjG9pXPOjXp6WA
PA8ZkWCaHLWao46DHdy6ZkKxLMhfN5XLKPyB8WYiW5Q50xCfN4uIHKuDkICdMl+T
K2d6DksiqO4j0dK+s4qTTTRqX0MnFAXaaSlxp2iDbovJ08b7HsWKCX04SE7JgIh8
T53iBGeDTQKBgQCoEphxPAk5o9wdwHJkFDKqVNKuuqZdsLQBLP+0YON3++jL9kSZ
ZCaoQorjtb+XWQwlDUo8tCQaJgY8bUUKQKAgaZWKB5K2hXDYYpaHa28B/dkC6V8Y
/0/+xjlpRrn+CnfidR34sqyoqQ8e+w4Ia5vvZoQ9ubtBTlgun5juhurHQwKBgQCA
W9O2J2/mvxaYLQwX0fWFBhEbY//Or0ekEixoB634qykqYR1O21O1GzVqr1hnQNML
0tctW0b4WVr369HIM+t28aBejFqyPMXLpLdhCC/CnI4alBWwrPOXssN3I02Qyb3m
oyPnkZtXpW+t5bGoxQBA71T/tKtGSkzqmcGdOd9z2QKBgQDJ4QgV8czQYa1B5moi
RmgGlFNiT0zyvPQSrCNL/AyQNGIoeBB/aKgm3ugM9keGQw59Ev+4dIubhGtn9oEm
p9XNlLSdYFhShZ8GiylLVvzQ2MCbEg8r6koTI2WDso1mIe4atSLVFEbaz90Yivrh
ZqiUNiukltim2BOCW/KEsI8mbg==
-----END PRIVATE KEY-----
"""

    /** SHA-256 of the leaf certificate DER bytes (RFC 5929 tls-server-end-point), as 32 bytes. */
    val certGoldenSha256: Array[Byte] =
        Array(
            0x01, 0xdc, 0x45, 0x19, 0xb3, 0x83, 0x2b, 0x1c, 0x36, 0xb3, 0x79, 0x1e, 0x67, 0xd5, 0x11, 0x40,
            0xa1, 0x4c, 0x6f, 0x23, 0x35, 0xb3, 0x96, 0xdb, 0xa2, 0xf0, 0x6e, 0x35, 0x06, 0x90, 0x85, 0x59
        )
            .map(_.toByte)

    /** Write the embedded cert and key to fresh temp paths under `/tmp` (which exists on every OS these backends run on) via the cross-platform
      * `kyo.Path`, returning the (certPath, keyPath) for `NetTlsConfig.certChainPath` / `privateKeyPath`. A fresh nanoTime-suffixed name per call
      * keeps concurrent or repeated runs from colliding.
      */
    def writePems(using Frame): (String, String) < Sync =
        val nano     = java.lang.System.nanoTime()
        val certPath = s"/tmp/kyo-tls-$nano-cert.pem"
        val keyPath  = s"/tmp/kyo-tls-$nano-key.pem"
        Abort.run[FileWriteException](Path(certPath).write(certPem).andThen(Path(keyPath).write(keyPem))).map {
            case Result.Success(_) => (certPath, keyPath)
            case other             => throw new RuntimeException(s"failed to write shared TLS test cert: $other")
        }
    end writePems

    /** A self-signed certificate for CN=wronghost.example (SAN DNS:wronghost.example), used to test hostname-verification REJECTION: a client
      * connecting to 127.0.0.1 (or verifying "localhost") against a server presenting this cert must reject the name mismatch even though the
      * chain validates (the test pins this cert as the client's CA via caCertPath). Distinct from the localhost cert above, whose name matches.
      */
    val wrongHostCertPem: String =
        """
-----BEGIN CERTIFICATE-----
MIIDOTCCAiGgAwIBAgIUbO5KaihTs9Y1eghcvdXrgCH1V3QwDQYJKoZIhvcNAQEL
BQAwHDEaMBgGA1UEAwwRd3Jvbmdob3N0LmV4YW1wbGUwIBcNMjYwNjA4MDcxNzU5
WhgPMjEyNjA1MTUwNzE3NTlaMBwxGjAYBgNVBAMMEXdyb25naG9zdC5leGFtcGxl
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEApo6p0rBxYHZMvGthzNfy
zgEJZNwNUWnKcdby1y+Sf3s8v4ceg+ZMBgOJ264j2dWzREnOAY0KIwJrnmMK82u9
a0GhmXrgGO037HmyY/8QU0wIh/inSH7XFKxw0SixO5pbdCs+v2fPR0rh3KmFm2e7
SnENxVQiPDS/doVgM56T1c73rAJ25Et7RncUAHe6bZCwc6/hDdiWYIktK3xQH14I
R2XMp8nX/xnulZBY2EBbG3muGOqyU52mHujL89b+VaOo/1MXj519zeHGqlyZiusc
kytdhoLA1G+0IDClGWDEVPD46okIv4/bP1yA5yVSpa4uzLnnbJtj134fP7XB4Aig
wwIDAQABo3EwbzAdBgNVHQ4EFgQU04iyqmHqLFcNIxPWgNNMdBd+cDQwHwYDVR0j
BBgwFoAU04iyqmHqLFcNIxPWgNNMdBd+cDQwDwYDVR0TAQH/BAUwAwEB/zAcBgNV
HREEFTATghF3cm9uZ2hvc3QuZXhhbXBsZTANBgkqhkiG9w0BAQsFAAOCAQEAWGWv
203BZexQlqGm7j+Vgh0PjNWrqYVe/ASc3c12qVAnpOyC3sB0YBAUocZgO5wIdVlD
vGmVS4WSU+NTIEhWUWNFsBfBSia7OryRp/v19Az4cXKJxuXWSllK1sF3XC7ooVUl
tmtqryPCOFn/Nly4F3YrtOxZKTLICKG/0wVx0NqJyJsmVVYVUbpb4PVkRu7lOh8z
qhiN27Uv+BeYJKgii7Fpuy08QNU1Xy2IIqf+ibJ3fkeKhserLtDG0uGURSIy373s
sA6mkLIF0fM8P0b1M2bM+Pxe5Qvyt0fXeahV+WMG5Hj5w9GX9P3ReDhEirUADN29
+/+MP6P16LRAeNRY4g==
-----END CERTIFICATE-----
"""

    val wrongHostKeyPem: String =
        """
-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCmjqnSsHFgdky8
a2HM1/LOAQlk3A1Racpx1vLXL5J/ezy/hx6D5kwGA4nbriPZ1bNESc4BjQojAmue
Ywrza71rQaGZeuAY7TfsebJj/xBTTAiH+KdIftcUrHDRKLE7mlt0Kz6/Z89HSuHc
qYWbZ7tKcQ3FVCI8NL92hWAznpPVzvesAnbkS3tGdxQAd7ptkLBzr+EN2JZgiS0r
fFAfXghHZcynydf/Ge6VkFjYQFsbea4Y6rJTnaYe6Mvz1v5Vo6j/UxePnX3N4caq
XJmK6xyTK12GgsDUb7QgMKUZYMRU8PjqiQi/j9s/XIDnJVKlri7Muedsm2PXfh8/
tcHgCKDDAgMBAAECggEASnGBXfYW9rJpYd3s/I2YrJKyDC5+lWDfZzpXl+5fYDNI
16Ig9Xs9h4KVX2baB0cItQD33qGXYkZ2q3hBMMN7CjFvdRYCi6GbWUqbfS5HsbNO
LNfqjPCEWW0pj5LMhINdVPLvPMW9U3QYt3Pdj7QxdfhJ22TbWGWLHgJHGUtLcEgz
+7r0/sxx0SldgxyKkcWqeGElmgB4ZO2RoMh5Em0yRZs+uILVjk0ThQa64/Mg65Rj
8NVNeUAH+Ma5KdjCIsQgH8jB7Iuq4VZGniS8ZFeUWQspQm45dLJ+fkynJPRDy0T+
X1lRzCE0RamrXdIaJx2DZvppiqWGyydf/7HFTGb4YQKBgQDiAurDsEYwOB3PO6r/
tXz8Y6C4u7oi3adSDv5erYPKHbr4XPmiTim8Scj1v+7ECpQY1F5EQX5TksL5Mk9k
6NB+hMcAyVwesS82xcxhSG6EJpvikXMHPX9sPspvAB4LATpyFEfn6CcctPRRRx1p
uukm7ITf09snWS36n/3vxMgtHwKBgQC8qDpWX5nlUC6zcVYQ5/fE/+lrjZmWqsmb
gGJzHWiEZ5C2Z6ziPFRELSK75pcdWjFpdEpxgmU6fTJtIIeZdtaRDBsol4CL2Zoy
Tj+Dg/+32J77T3fd67aJYq7FsYtcYHdFz7hKka0MpcMscsguVW41CabibTxlUZ4J
pOlQJbOz3QKBgFthlo5cvWRNrC/YDkGpnclmdtt6e74RJM/W5B5fxcN41doJrZ1k
QReyNaC3Y9C7/jkz1JGAcZVU56ReJR/Fylb9VIEK6UY3mcFppENJR/YCrlCjQoEQ
6m5XzP2obH1Cl+D8Nj6b7QR8XbRnLotLWW21f9wICroUIrUM7118kPs9AoGBAJ37
fobQHg7i24jXMwyLRHhLGcxAUsrSEGxQ0aC2ksy18YBeR29Yt/Qzm++gBRHGcrRt
dt2hJWYaa3zpDcScuMfUTHXskPAL9E2GKzfV9PGezFuFS8qiVkSsR9EzgZGFErx6
W0jOvwxlT5DMOgha8CQoBgF9GmN6Oo62885zFA5dAoGAbcJRpLv1UXj9OIk+6GXd
fa95kyQx+oFitDDAuCWkNnEXve/a9w3nQIx0YsP2Av9YV1zr0GxuUTGUxJGr+Ihs
p64kZoNWjna+t4+SY5FvVTFVy6cG+CiEfRf9ATBtqo8xTjVtnqm7fcSUlWsKTR08
OnBE4RP7UrqA7cRm1tkCj+Y=
-----END PRIVATE KEY-----
"""

    /** Write the embedded wrong-name cert + key to fresh temp paths, returning (certPath, keyPath), mirroring [[writePems]]. */
    def writeWrongHostPems(using Frame): (String, String) < Sync =
        val nano     = java.lang.System.nanoTime()
        val certPath = s"/tmp/kyo-tls-wrong-$nano-cert.pem"
        val keyPath  = s"/tmp/kyo-tls-wrong-$nano-key.pem"
        Abort.run[FileWriteException](Path(certPath).write(wrongHostCertPem).andThen(Path(keyPath).write(wrongHostKeyPem))).map {
            case Result.Success(_) => (certPath, keyPath)
            case other             => throw new RuntimeException(s"failed to write wrong-host TLS test cert: $other")
        }
    end writeWrongHostPems

end TlsTestCertShared
