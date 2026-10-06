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

    // A /tmp name must be unique per call: nanoTime alone can tie across concurrent callers (its
    // granularity is platform-dependent, about 40ns on an aarch64 VM, and a suite start fans a
    // whole backend x provider matrix out at once), and a tied name means one cell
    // truncate-rewrites the very pem another cell's TLS setup is reading, which surfaces as a
    // truncated-key handshake failure. The counter makes the name unique within the process; the
    // nanoTime keeps names from colliding across processes sharing /tmp.
    private val pathSeq = new java.util.concurrent.atomic.AtomicLong(0)

    /** A unique component for a /tmp path minted by a test fixture; see the note on `pathSeq`. */
    private[net] def uniquePathTag(): String =
        s"${java.lang.System.nanoTime()}-${pathSeq.incrementAndGet()}"

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
      * `kyo.Path`, returning the (certPath, keyPath) for `NetTlsConfig.certChainPath` / `privateKeyPath`. The name carries `uniquePathTag()`, so
      * concurrent cells never share a path and repeated runs never collide.
      */
    def writePems(using Frame): (String, String) < Sync =
        val tag      = uniquePathTag()
        val certPath = s"/tmp/kyo-tls-$tag-cert.pem"
        val keyPath  = s"/tmp/kyo-tls-$tag-key.pem"
        Abort.run[FileSystemException](Path.run(Path(certPath).write(certPem).andThen(Path(keyPath).write(keyPem)))).map {
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
        val tag      = uniquePathTag()
        val certPath = s"/tmp/kyo-tls-wrong-$tag-cert.pem"
        val keyPath  = s"/tmp/kyo-tls-wrong-$tag-key.pem"
        Abort.run[FileSystemException](Path.run(Path(certPath).write(wrongHostCertPem).andThen(Path(keyPath).write(wrongHostKeyPem)))).map {
            case Result.Success(_) => (certPath, keyPath)
            case other             => throw new RuntimeException(s"failed to write wrong-host TLS test cert: $other")
        }
    end writeWrongHostPems

    /** A self-signed localhost certificate whose own signature hash is not SHA-256, with the RFC 5929 tls-server-end-point hash of its DER
      * computed with that hash.
      */
    final case class SignedCert(signatureAlgorithm: String, certPem: String, keyPem: String, endPointHash: Array[Byte])

    /** P-384 key, signed with `ecdsa-with-SHA384`: its tls-server-end-point hash is SHA-384, 48 bytes. */
    val ecdsaSha384: SignedCert = SignedCert(
        "ecdsa-with-SHA384",
        """-----BEGIN CERTIFICATE-----
MIIB2DCCAV6gAwIBAgIUC2eqW8uFsTe3vurLoKiCiZ+BR1MwCgYIKoZIzj0EAwMw
FDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MTAwNDA1MjE0MFoYDzIxMjYwOTEw
MDUyMTQwWjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwdjAQBgcqhkjOPQIBBgUrgQQA
IgNiAASJBUlc5+8Xz1xKAQVT+iaenNqbzPU65Vf5d9Fltc7tmlfy9SuVx9nlNioQ
XxjAOS70l/CxvpTnn8rCCnrrlguK/WcMG0kTt7tE237x6Etgyuw/6UuW+fy1i9vo
mvXw1JGjbzBtMB0GA1UdDgQWBBTzTa50DW3khgq53v7cpso3hvl8FzAfBgNVHSME
GDAWgBTzTa50DW3khgq53v7cpso3hvl8FzAPBgNVHRMBAf8EBTADAQH/MBoGA1Ud
EQQTMBGCCWxvY2FsaG9zdIcEfwAAATAKBggqhkjOPQQDAwNoADBlAjEAma9ls6l2
dN4NzFxd6bg0/88cBHt699BsvxwgMuAGKvR4YBTo7XxBEjCYSWfX6G5JAjA1XVFW
LgUku3cLF8SaGYzhernIuRrz9r9JJPgviEoHf7sc/hDTPnKp8nPJrZokibA=
-----END CERTIFICATE-----
""",
        """-----BEGIN PRIVATE KEY-----
MIG2AgEAMBAGByqGSM49AgEGBSuBBAAiBIGeMIGbAgEBBDDwTX2nH2sfMGHquVLM
gp0OOit8e/NKsEylGqzqaqc7N5ZcaJu3ExEn7zf4Emf7hG2hZANiAASJBUlc5+8X
z1xKAQVT+iaenNqbzPU65Vf5d9Fltc7tmlfy9SuVx9nlNioQXxjAOS70l/CxvpTn
n8rCCnrrlguK/WcMG0kTt7tE237x6Etgyuw/6UuW+fy1i9vomvXw1JE=
-----END PRIVATE KEY-----
""",
        Array(
            0x0f, 0xb1, 0xa5, 0xdd, 0x9f, 0xfe, 0x6d, 0x5b, 0x0c, 0x0d, 0xa3, 0x14, 0xe3, 0x8c, 0xb7, 0x15,
            0xea, 0xa1, 0x87, 0x49, 0x79, 0x6d, 0x1e, 0xb9, 0xc2, 0x52, 0xda, 0xd5, 0xd4, 0x71, 0xfb, 0xe1,
            0x46, 0x6f, 0xff, 0xc3, 0x4f, 0xfa, 0x0b, 0x15, 0x0c, 0x2d, 0x4f, 0xad, 0x77, 0x09, 0xb8, 0x4d
        ).map(_.toByte)
    )

    /** RSA 2048 key, signed with `sha512WithRSAEncryption`: its tls-server-end-point hash is SHA-512, 64 bytes. */
    val rsaSha512: SignedCert = SignedCert(
        "sha512WithRSAEncryption",
        """-----BEGIN CERTIFICATE-----
MIIDJzCCAg+gAwIBAgIUYwusfEmLXdGPIP9hyTNkyCLu5KcwDQYJKoZIhvcNAQEN
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MTAwNDA1MjE0MVoYDzIxMjYw
OTEwMDUyMTQxWjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQCbkP/V2emJuN/Rcc0k+7razTqoi1vtWPa+/5GfadIE
2ExeteZio5R3vuny3HXd/Sn7BpyhQ4K35YWEKQ8umh6VNQGu5f2DW4ngf/FAjge9
nwfozhv2cCQE3z+Z70x7mGG+xOW5c5U7Ud4fbdDAn/1HoVERgsYy2L2ZoYxDKmi5
3nfcKcCTRVYy6snEuqwuQUKyR1GFrytK092DbwHxQHvb0z4sCFVClu0Ipr/wxFbt
N0ByZakyfZ/nVobm4NgroEw8ETrNq6qiNLEepaiE7d77PJ5ADMN9FqApBbFYeJ+X
k2i8As9ntNuT2SuDm8Oac/s4ig7TEnw42jgOc2CqEvxJAgMBAAGjbzBtMB0GA1Ud
DgQWBBSFGIm01udI+dHbhymASexkj9waETAfBgNVHSMEGDAWgBSFGIm01udI+dHb
hymASexkj9waETAPBgNVHRMBAf8EBTADAQH/MBoGA1UdEQQTMBGCCWxvY2FsaG9z
dIcEfwAAATANBgkqhkiG9w0BAQ0FAAOCAQEAdhCsEbUvzQNktNlUZZmcRBOlfba+
0lABh7sYuOTepnD7OnApOB4BDrSmNcLtSTP5q2mqRHh+jf9CefJT4lXQbvtdyMjP
QQj8+IV90tM6xcsyuckwH5GzwBFQbUwwjsGa+zYARi3FRJ3Qup+0clA1HGBSju7n
bjycyRi3uOizm/n5zZrSO9U52Zl3YzWlJw8L8wEHR+jnoPAPVCmqpF2HSlwCvLfF
pzzNg86npnp+Ci1iHXhi848IJ19iK3PJFPvU5n+D10FoDXnjSOmCj3rDZJGQEBJA
KnXK5De3sWDVUpHpc/ZW5yvUSsagB3tlqlYfVpXs8fn9TddJomez0srgTg==
-----END CERTIFICATE-----
""",
        """-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQCbkP/V2emJuN/R
cc0k+7razTqoi1vtWPa+/5GfadIE2ExeteZio5R3vuny3HXd/Sn7BpyhQ4K35YWE
KQ8umh6VNQGu5f2DW4ngf/FAjge9nwfozhv2cCQE3z+Z70x7mGG+xOW5c5U7Ud4f
bdDAn/1HoVERgsYy2L2ZoYxDKmi53nfcKcCTRVYy6snEuqwuQUKyR1GFrytK092D
bwHxQHvb0z4sCFVClu0Ipr/wxFbtN0ByZakyfZ/nVobm4NgroEw8ETrNq6qiNLEe
paiE7d77PJ5ADMN9FqApBbFYeJ+Xk2i8As9ntNuT2SuDm8Oac/s4ig7TEnw42jgO
c2CqEvxJAgMBAAECggEACV2rzsqQK+bXMCXuLZQrm3FltFtc4nz2W2jJnKkN/ZVZ
gQ4/MPXGQjCv/HDXdnuqeQmHM+emSWoRJXQkt4U3OCZ5KSLW+39EXXbL0JDR2Hxo
SSo3jBkTohN1dyVtcQzV6Fq0YKZGeCF6rQu7Lg2z0wncS/XtTDG5CzSAXFQuT++u
jJVlAy72KZYU8CPrrCTcgTaxL46bDj+afetSuhJ4ZHcRyzaMef7HMvs9XAx4Ckhu
EFLoBITDmoJaImfU8qW4Kv4s5UNgurc5UNuo3q2oTc/W7dnZ46QcSarQIi52Svk7
2dRahCUfzrmaVOH6KOspy2Ea44FZgB5y0EJm4Iqh8QKBgQDJDw31f2iPnoa7m0Fw
fG1bl/BKYjXvdy90FuDJuNHs5XzV/L5dNfaEPMgt/ZwAhbKff868ArOc2by8KTO2
yPtR7MDe5JxwB/ICWxmuIzXmdXBoUIgLZibC2acco7ogfrm590LYjBmZ9ROWeosT
rQy1bjthHlGuFZ54emS5SQscjQKBgQDGE407ZJORMKcFpezSEE21LsPJSl9kj12v
uLHn2aCjZ3BdRC4cm2nwkLZi73hwQ82IZSwAaEAAYBFZGzGufiHoa9bjB5nQ3JJ8
EIp1zqnT67eHxWGoI964BIh8ShV4td9GHGQoDHxEjLVLFRzyb/0PFO1ncXo2yEt0
OhMUGiW1rQKBgQC7SLmw/+BvP7SzVtirJkxbsHlVYIxrJrNeSN1VKkLpj48saUUN
4HFkFpZFOOKzHdcYed4iBcY3ih8jiqGwVyC73HSAa6VJOi11glS2f/f6V1TA5psD
O0FJ6aKfq+d503G/x5JN0psabU60siuQxXZ8HlVTjwF4zoySHzhp259tFQKBgC6/
9pmKB3pBLWqb2uVJi82zl+oub41geRA8W2EJcGygwViB+xAtbjelMCbxtk9o8V27
40LFWDW+dtm9HWC9zGr66OD0rk0pgjld/hAIEvU9sTeOUppIvQxZpY0QPzkaU/RM
RydcqwfS3gc2mHpwDB4/JjlAA0RiycxI2K/p6/SRAoGBAKr7+U2S3v/KpmGAiV+o
zvdR5BYVOEZScx2ddSOJ9oe1uUlCjxOm82rZiS4bDwSHoN4w7bnKPTWQs0mpjQ53
ixPfEav2j/msO1RFPWhSOXVwL2IMZh5DuIde8SnJkFz1SvWKwqiJBEL6goAtGjhx
stl71P5LQvj25ak33JCT8QnM
-----END PRIVATE KEY-----
""",
        Array(
            0xa7, 0x00, 0xb4, 0xcf, 0x04, 0xca, 0x6d, 0x27, 0x57, 0xb6, 0x40, 0x0c, 0x21, 0xd8, 0xa5, 0x4c,
            0xce, 0x2f, 0x65, 0xf7, 0x36, 0x7b, 0x5c, 0xec, 0xb7, 0xd6, 0xcb, 0xb3, 0x37, 0x25, 0xe2, 0xf7,
            0xc4, 0x85, 0xe8, 0x9f, 0xe4, 0x66, 0x78, 0xb8, 0xb1, 0x1e, 0xd9, 0x04, 0x48, 0xfe, 0x52, 0xde,
            0x18, 0xb3, 0xff, 0xbd, 0x38, 0x6f, 0xd2, 0xbe, 0x4c, 0x88, 0x50, 0xa6, 0x06, 0xdb, 0xff, 0x0a
        ).map(_.toByte)
    )

    /** Write `cert`'s PEMs to fresh temp paths, returning (certPath, keyPath), mirroring [[writePems]]. */
    def writeSignedPems(cert: SignedCert)(using Frame): (String, String) < Sync =
        val tag      = uniquePathTag()
        val certPath = s"/tmp/kyo-tls-signed-$tag-cert.pem"
        val keyPath  = s"/tmp/kyo-tls-signed-$tag-key.pem"
        Abort.run[FileSystemException](Path.run(Path(certPath).write(cert.certPem).andThen(Path(keyPath).write(cert.keyPem)))).map {
            case Result.Success(_) => (certPath, keyPath)
            case other             => throw new RuntimeException(s"failed to write the ${cert.signatureAlgorithm} TLS test cert: $other")
        }
    end writeSignedPems

end TlsTestCertShared
