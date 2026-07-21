package kyo.net

import kyo.*
import scala.scalajs.js

/** Tests for Connection.upgradeToTls on the JS (Node.js) transport.
  *
  * Uses a loopback TCP pair where both client and server run in-process. Certificates are embedded as string constants, no subprocess or
  * filesystem dependency required for cert generation.
  *
  * The upgrade protocol used in these tests adds one round-trip before the TLS handshake:
  *   1. Client sends 'U' (upgrade request).
  *   2. Server receives 'U', sends 'R' (ready), then calls upgradeToTls.
  *   3. Client receives 'R', then calls upgradeToTls and sends the TLS ClientHello.
  *
  * This mirrors how Postgres STARTTLS works (SSLRequest → 'S' acknowledgment → TLS). It ensures the server has completed its side of the
  * upgrade before the client initiates the TLS handshake, necessary because Node.js sends the ClientHello synchronously when tls.connect
  * is called, leaving no event-loop tick for the server to upgrade first.
  *
  * Server-side TLS uses PEM content written to temp files via Node.js `fs.writeFileSync`. The client CA cert is also written to a temp file
  * so `tls.connect` can validate the chain when `rejectUnauthorized` is true.
  */
class ConnectionUpgradeTlsTest extends Test:

    // Self-signed certificate for CN=localhost with SAN=DNS:localhost,IP:127.0.0.1
    // Valid for 100 years from 2026-05-02
    private val localhostCertPem: String =
        """-----BEGIN CERTIFICATE-----
MIIDJzCCAg+gAwIBAgIUPbWX6KnoJOLHRuKONfRkYaUU2NgwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MCAXDTI2MDUwMjIzMDYwOFoYDzIxMjYw
NDA4MjMwNjA4WjAUMRIwEAYDVQQDDAlsb2NhbGhvc3QwggEiMA0GCSqGSIb3DQEB
AQUAA4IBDwAwggEKAoIBAQCarvRDNO1FpP6O1tZSItymexP6mEnxano2Z2pZzu8R
PoItB9Qzhu7bEO+kFKYDmEQug8fMOyMFKKuVd/xZCZkxo1cuD0YILXRv52FEGTdw
YPK2hbiL6pM6eDbUFby5KUVzOrBUwm0ER3Yqn9O5VJ58PdIOpZUz+xMUiRdqZawD
aa83zOdqZ3+2oBY+eOFw9IahZrrS1Q3SpxfDAmqwShgCUtVS+2Js9G3R1JUorU1j
Sc67mo4l2FwWfIAJBZFR57ccbTSviZIZOnJJxI6SahnrVc7KeeWDlJcTbso8Gxsc
NtNFiAvcLrnBAmAnMYTFnXRwUq6jz+Ab9f9yqn/e3XNNAgMBAAGjbzBtMB0GA1Ud
DgQWBBSqW+YHlrDGCLf3dtOzZNMBzarJtzAfBgNVHSMEGDAWgBSqW+YHlrDGCLf3
dtOzZNMBzarJtzAPBgNVHRMBAf8EBTADAQH/MBoGA1UdEQQTMBGCCWxvY2FsaG9z
dIcEfwAAATANBgkqhkiG9w0BAQsFAAOCAQEAQtLbfQl2JCxat+v9VVhr1iW5zR0x
v7GcItWG2PVs3AERsf0PsMQF9geUhJKA8134PPtyPfEGbvCG+AGL8DldCtwa73Ya
XJgRN0zVJgz8UCL7tIg38UsV/QnbCBQ7U9Y2zViiocKsuXFC6RL8s04hxiqQRqeF
KxRuhsnvPXHC4vN/x+RAwVMJANYD0Cg2u3DKo4V+98Nn9pwVZASmezp3Lhl7mNEn
xvLW4UF+w2TWb9uyeSz8YI/djbI/W0uimPD3fUwM+lSZX6osJrfXZXdgVYiR5C13
92GIyHNuExuX1KLDMtpzx0/m331FGx63ryewsQ9Q033cZon5yIXzD3JcZg==
-----END CERTIFICATE-----"""

    private val localhostKeyPem: String =
        """-----BEGIN PRIVATE KEY-----
MIIEvAIBADANBgkqhkiG9w0BAQEFAASCBKYwggSiAgEAAoIBAQCarvRDNO1FpP6O
1tZSItymexP6mEnxano2Z2pZzu8RPoItB9Qzhu7bEO+kFKYDmEQug8fMOyMFKKuV
d/xZCZkxo1cuD0YILXRv52FEGTdwYPK2hbiL6pM6eDbUFby5KUVzOrBUwm0ER3Yq
n9O5VJ58PdIOpZUz+xMUiRdqZawDaa83zOdqZ3+2oBY+eOFw9IahZrrS1Q3SpxfD
AmqwShgCUtVS+2Js9G3R1JUorU1jSc67mo4l2FwWfIAJBZFR57ccbTSviZIZOnJJ
xI6SahnrVc7KeeWDlJcTbso8GxscNtNFiAvcLrnBAmAnMYTFnXRwUq6jz+Ab9f9y
qn/e3XNNAgMBAAECggEAEJvqlHDRbWH7B2FgH++oW6PpkEXb4rU5LDBMhhonJ2Xb
2nP1wBaj/RfDc43YCZjG8Wuq/232I66rReBdUz4pmd/dE5AGSAiBcSaaFhLTFhkC
1sBjAXsNlgCken9fBU6+K1JI7sD1rbjyoj8JH+RLJDILJRF4hN1XviO+ROln3/HF
TUStjkJgdNsGbaA6R1oTt8PQiYiwazH2JpBQdwdoVTlOp3leHsVR8za0NsTUH78g
elMRGMzhjhuQjhJ7yTtxPBxfEL1POEVVuYOo9Ztq0kDy9afTlSa4XXgK9n6NvpNA
xjCIo2TnJ7yQnj5IYT/aQZo/4Is5NxL/ERkU4WmeDQKBgQDVSx9f0eIVZVZyRep8
CdDZ830sopcKhxleEpXhSwfi09B/taU4PuL/f0j5vJh45MvFfvLrVLXBmZqm8m4+
o8zHuf3GIsjkvBd4S9+/sdgbVR16SiHHllorG7vsmSvXU25qewqqHdDW9pV1Jc5m
e7Z5VyKF5kM7YDLMGcpqfvtd+wKBgQC5p6GH7OxXnDYO5uN0OXh4xaYGVggsaGNw
sYjpUJmTx+WLdrN99MFC8Ggx1S3c4HPE3TZopspoUkcjya8oDdjH5J06DPm4nsfi
Ie53vU4hvbMjZ2VZqXqvVblTVv4ZSe3LXMJTmEISfzQeBBU1OXygUN2+MYW9fy78
oSfATVkZVwKBgFsXczXD+C81ET9KdsM0mfqLD5mBcsovOnk/rL0EA0EvPutb00z7
Jo0BelQV5HQ1GoWlGu/ARMOC61aDUOv1np5p42S8NFnjro5UsSE4PdgmeKligZyw
rJ+ef5qjK+MRIaXeGIbgpvE3bEsUs1p3WU3nFbpjxbDU+7zJAvaKdqYDAoGABd6e
5MVA06REsUPrfQk0Hd1h2mFt8Ll8K1vxkC7ULC/tufMOybSrR4qCQNEUmh8eieLU
jYp4a8PuK9t8hi+p4uf/cI5odsXOW//mojKB/d8Zgs0KD0OkdVaofVIKxpHnyr4s
BqiRrjQHWcuXQA+JOShVWmYGbG9Q9PvDXfkUEBECgYAj8GoO63Pud5OA1enhczGQ
AnV7Wkdru2fvyCCyfNekq34qPURCfmcDs0ky7wVdMwFQvlIijza2r7Bw9wZUlMIn
Ce5bNSaAgZT8VC8LQ23hC8onj2+K+iECzdGPG5maVLm0Jc6cDOkDiGHDUmo1jYW4
AQMQybEAKz79SY2peNgJSg==
-----END PRIVATE KEY-----"""

    // Self-signed certificate for CN=wrong-host.example.com with SAN=DNS:wrong-host.example.com
    // Used to test hostname verification rejection.
    private val wrongCertPem: String =
        """-----BEGIN CERTIFICATE-----
MIIDSDCCAjCgAwIBAgIUXbrWkhDNkUrL9YKNaDmg+fQdCz4wDQYJKoZIhvcNAQEL
BQAwITEfMB0GA1UEAwwWd3JvbmctaG9zdC5leGFtcGxlLmNvbTAgFw0yNjA1MDIy
MzA2MTJaGA8yMTI2MDQwODIzMDYxMlowITEfMB0GA1UEAwwWd3JvbmctaG9zdC5l
eGFtcGxlLmNvbTCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBALdxr+JH
7KU7179NjHOEcGxshMCI99Ew3fqYWGwfxlaHoZcWM0XlMYvnekVCmSH2sp5qNWCz
ohG5dR4THWvValNxcZi3j2Km+IyZCeidDqlUa2aUQ+yvT3v9lJyXbOpy9HecxwFh
xDIAAdgzPaqM/GHdBM3N+Z1hzk1bSyXvKqnB/FRAD3K86HwhvT/lz0REjmheucFG
SP7JW/XQVykvgk6SQ613GaCuXN6j7OfUKX1K6OtKsdbX/dS2+fEg/4agEIURlL9A
RbOZix8Qmj7iBKMT0wLsu5rtwfy0gOC0lbigMF1efZSK/aHCUZmGtzMd49QcQxCm
0Ao+U31NCMKo608CAwEAAaN2MHQwHQYDVR0OBBYEFKnJqKxO5jKEEjkGk6yzUGNc
8RYpMB8GA1UdIwQYMBaAFKnJqKxO5jKEEjkGk6yzUGNc8RYpMA8GA1UdEwEB/wQF
MAMBAf8wIQYDVR0RBBowGIIWd3JvbmctaG9zdC5leGFtcGxlLmNvbTANBgkqhkiG
9w0BAQsFAAOCAQEAdBa4LTsPzd2wvwjPcu/EJO1ggig0FHMJYKaDepKlccz2o/jP
TZMPzmabYA8HyhUlXreqY4WfKfTDyfZmDlWuYu0WXVAq0PdNUFnkaeHiRtRl/fVN
4FUoNmidQRdtG3Bt3uTufzUp3LToVPixixo05vYvX651fifUbOi4iYHmhBT0DiUa
5g4nlmbpAWweig9wtH75ylhKf5Ox44XVeSED/g28cWu162xYz/FfnkqFxp5RzNgJ
C9xY+pS8Tch6H6o3+jgGgWoxJjHiZmKzvpZdNmhomtF5DFwrlCaPpMbtZlTlO7On
GrSe9jOjkWAdHlPUgKcpGEDJR75KpysjPOISaQ==
-----END CERTIFICATE-----"""

    private val wrongKeyPem: String =
        """-----BEGIN PRIVATE KEY-----
MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQC3ca/iR+ylO9e/
TYxzhHBsbITAiPfRMN36mFhsH8ZWh6GXFjNF5TGL53pFQpkh9rKeajVgs6IRuXUe
Ex1r1WpTcXGYt49ipviMmQnonQ6pVGtmlEPsr097/ZScl2zqcvR3nMcBYcQyAAHY
Mz2qjPxh3QTNzfmdYc5NW0sl7yqpwfxUQA9yvOh8Ib0/5c9ERI5oXrnBRkj+yVv1
0FcpL4JOkkOtdxmgrlzeo+zn1Cl9SujrSrHW1/3UtvnxIP+GoBCFEZS/QEWzmYsf
EJo+4gSjE9MC7Lua7cH8tIDgtJW4oDBdXn2Uiv2hwlGZhrczHePUHEMQptAKPlN9
TQjCqOtPAgMBAAECggEAChEimlPhIDY3AkgSr5oygzi3VkuN8fxxChoHLDPYMhJy
YzS0Lp9Dx042LwvXFH6R0dC5SivBdqCLVVGuOSc2lQqyNbPJGhxYonqZiRrpXZCE
7lWvnpMGVSMW3zsOHpWdZd0ELMa9OhM3BrLVLQFcrlAvPc4SZzkhVaxvZM8Js9Qa
KD5vs6TrmMCAAKcPeq7eWIsaO2dAZB42NS4Jb27l2nWRJ6nxzxJxxRfQ6s8W2bHI
847T+OmpiiuEoUVgNKTq4yt1iRMqVXtYjKsSi/uTzmjde3Xr0v9j2ugcDODgT5GP
q/QqeMzEcnl74RrTKSGvEuAfo461/J96TmUPpTM/SQKBgQDaLIsj5ZyAZI14Unay
azeZNZk0+URShWdTqAuua7+dtCuSt8fEleKxhUIJiwmVOR+kvrSXHMBYB6w+Ggfs
AGv3qMhYO8VhE/PRDSRcw7OXkZiNPMMi6aOyI1EtBKsBnFYH551mzuTGe5UcPUOw
3OyARBx6E7a3atqpGD1HX+HpWQKBgQDXP7AVb2ZuTuEgOSE7ZwfUrNl3FCLc28hL
nMvpTXt5l/yWv7kfFhhgm258f33DpvUorJWRgn2wIk/UF5Y/nnecAQarDeA5iACx
a6wpnjF3WtIVqygLuce0pWSu3UnOKXeW7kYqdLNCafsSguXwBlb1HzxJj/i0soD2
GqbnacK85wKBgQCVgMKUBaq5vK3IztFxr9D4lub2iv1LgwjsJZJjoog63BSjxHYK
+x7NwOAgw/r8G5nYfaoohtYzPIbGQ3Y8PYm+uxUWiVbXECIalePeAWkWqvmbhxC8
4td+oX7l13YLc4LshcWxhoL0lAj4we6ZyrnxpzrQWKPzijBugSK0vzqCeQKBgEN8
0HSoDPGhAazcvLKnRylDWJuhUJz2vAIZE0X+6Svin2uUiTJZ9yKzGR0nzALAIjWy
huKsi0PiPi09h/pCcTYUjEatfxQKtEzseXpDAzds3lm05EVo5liZUswQzcc58Sj9
ZDqDhIDaQcI4EGgFVNZeCMT3wv7EyPJgwNvlqQ0ZAoGADBWPbojaEAVN9o8oF3Qq
AWVCeKjb58GoE4W4kvJ14x4LO1sFvdjZIy4DOE9UiXVAq9LIHYi2E7BtrL+pLP8e
RBe0gzdjTAIk90G8Is39YXjD4VMx9YaZ/Y0OdiK3kok3edx3kYU/LA+gk7abTme0
Q9tsIulo26Tkvg3Xkhq+I5Y=
-----END PRIVATE KEY-----"""

    private val fs       = js.Dynamic.global.require("fs")
    private val os       = js.Dynamic.global.require("os")
    private val nodePath = js.Dynamic.global.require("path")

    /** Write a PEM string to a temp file using Node.js fs, returning the absolute path. */
    private def writeTempPem(content: String, name: String): String =
        val dir  = os.tmpdir().asInstanceOf[String]
        val path = nodePath.join(dir, name).asInstanceOf[String]
        fs.writeFileSync(path, content)
        path
    end writeTempPem

    // Temp file paths, written once lazily and reused across tests.
    private lazy val localhostCertPath: String = writeTempPem(localhostCertPem, "kyo-js-tls-localhost-cert.pem")
    private lazy val localhostKeyPath: String  = writeTempPem(localhostKeyPem, "kyo-js-tls-localhost-key.pem")
    private lazy val wrongCertPath: String     = writeTempPem(wrongCertPem, "kyo-js-tls-wrong-cert.pem")
    private lazy val wrongKeyPath: String      = writeTempPem(wrongKeyPem, "kyo-js-tls-wrong-key.pem")

    /** TLS config for the server using the localhost cert. */
    private def serverTls: NetTlsConfig =
        NetTlsConfig(certChainPath = Present(localhostCertPath), privateKeyPath = Present(localhostKeyPath))

    /** TLS config for the server using the wrong-hostname cert (for rejection tests). */
    private def serverTlsWrong: NetTlsConfig =
        NetTlsConfig(certChainPath = Present(wrongCertPath), privateKeyPath = Present(wrongKeyPath))

    /** Client TLS that skips all validation (trust-all). */
    private def clientTrustAll: NetTlsConfig = NetTlsConfig(trustAll = true)

    /** Client TLS with hostname verification enabled, trusting the localhost self-signed cert. */
    private def clientVerifyHostname: NetTlsConfig =
        NetTlsConfig(
            caCertPath = Present(localhostCertPath),
            hostnameVerification = true,
            sniHostname = Present("localhost")
        )

    // Upgrade signal and acknowledgment bytes used in all tests.
    private val upgradeRequest: Span[Byte] = Span.from(Array[Byte]('U'))
    private val upgradeReady: Span[Byte]   = Span.from(Array[Byte]('R'))

    /** Server handler that participates in the upgrade protocol: reads the upgrade request, sends the ready signal, then upgrades to TLS.
      */
    private def serverUpgradeAndEcho(serverConn: Connection, serverTlsConfig: NetTlsConfig): Unit < (Async & Abort[Closed]) =
        serverConn.inbound.take.flatMap { _ =>
            // Acknowledge the upgrade request so the client knows the server is ready for TLS.
            serverConn.write(upgradeReady).andThen {
                serverConn.upgradeToTls(serverTlsConfig).flatMap { tlsConn =>
                    tlsConn.inbound.take.flatMap { data =>
                        tlsConn.write(data)
                    }
                }
            }
        }

    /** Server handler that acknowledges but does NOT upgrade (used to test client-side failure). */
    private def serverAckAndClose(serverConn: Connection): Unit < (Async & Abort[Closed]) =
        serverConn.inbound.take.andThen {
            serverConn.write(upgradeReady).andThen {
                serverConn.close
            }
        }

    /** Client helper: sends upgrade request, waits for server ready signal, then upgrades. */
    private def clientRequestUpgradeAndWait(conn: Connection, clientTlsConfig: NetTlsConfig): Connection < (Async & Abort[Closed]) =
        conn.write(upgradeRequest).andThen {
            conn.inbound.take.flatMap { _ =>
                // Server sent ready signal: now safe to initiate TLS ClientHello.
                conn.upgradeToTls(clientTlsConfig)
            }
        }

    "Plaintext connect + upgrade succeeds against TLS-enabled server" in run {
        val transport = NetPlatform.transport
        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed](serverUpgradeAndEcho(serverConn, serverTls)).unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    clientRequestUpgradeAndWait(plainConn, clientTrustAll).flatMap { tlsConn =>
                        tlsConn.write(Span.from("hello".getBytes)).andThen {
                            tlsConn.inbound.take.map { received =>
                                assert(new String(received.toArray) == "hello")
                            }
                        }
                    }
                }
            yield result
        }
    }

    "Read/write after upgrade is encrypted" in run {
        val transport = NetPlatform.transport
        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed](serverUpgradeAndEcho(serverConn, serverTls)).unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    clientRequestUpgradeAndWait(plainConn, clientTrustAll).flatMap { tlsConn =>
                        val msg = Span.from("hello-tls-js".getBytes)
                        tlsConn.write(msg).andThen {
                            tlsConn.inbound.take.map { received =>
                                val decoded = new String(received.toArray)
                                assert(decoded == "hello-tls-js", s"expected 'hello-tls-js', got '$decoded'")
                            }
                        }
                    }
                }
            yield result
        }
    }

    "Upgrade against non-TLS server fails" in run {
        val transport = NetPlatform.transport
        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    // Server sends ready signal but does NOT upgrade to TLS, closes the connection.
                    // The client's TLS handshake sees EOF and must fail.
                    Abort.run[Closed](serverAckAndClose(serverConn)).unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(upgradeRequest).andThen {
                        plainConn.inbound.take.flatMap { _ =>
                            // Server acknowledged; now try TLS against a non-TLS peer.
                            Abort.run[Closed] {
                                plainConn.upgradeToTls(clientTrustAll)
                            }.map { outcome =>
                                assert(outcome.isFailure, s"upgrade against a plain server must fail, got: $outcome")
                            }
                        }
                    }
                }
            yield result
        }
    }

    "Hostname verification accepts matching cert" in run {
        val transport = NetPlatform.transport
        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed](serverUpgradeAndEcho(serverConn, serverTls)).unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    // Cert has SAN=DNS:localhost; client SNI="localhost", must succeed.
                    Abort.run[Closed] {
                        clientRequestUpgradeAndWait(plainConn, clientVerifyHostname).flatMap { tlsConn =>
                            tlsConn.write(Span.from("verify-ok".getBytes)).andThen {
                                tlsConn.inbound.take.map { received =>
                                    assert(new String(received.toArray) == "verify-ok")
                                }
                            }
                        }
                    }.map { outcome =>
                        assert(outcome.isSuccess, s"hostname-verified upgrade must succeed for matching cert, got: $outcome")
                    }
                }
            yield result
        }
    }

    "Hostname verification rejects non-matching cert" in run {
        val transport = NetPlatform.transport
        // Server uses a cert issued for wrong-host.example.com.
        // Client trusts the wrong CA (self-signed) but enforces hostname against "localhost".
        val clientCheckingHostname = NetTlsConfig(
            caCertPath = Present(wrongCertPath),
            hostnameVerification = true,
            sniHostname = Present("localhost")
        )
        Scope.run {
            for
                listener <- transport.listen("127.0.0.1", 0) { serverConn =>
                    Abort.run[Closed] {
                        serverConn.inbound.take.flatMap { _ =>
                            serverConn.write(upgradeReady).andThen {
                                // Server upgrade may fail or succeed depending on timing; ignore result.
                                Abort.run[Closed](serverConn.upgradeToTls(serverTlsWrong)).unit
                            }
                        }
                    }.unit
                }
                port = listener.port
                result <- transport.connect("127.0.0.1", port).map { plainConn =>
                    plainConn.write(upgradeRequest).andThen {
                        plainConn.inbound.take.flatMap { _ =>
                            // Node.js verifies hostname "localhost" against SAN=DNS:wrong-host.example.com, must fail.
                            Abort.run[Closed] {
                                plainConn.upgradeToTls(clientCheckingHostname)
                            }.map { outcome =>
                                assert(outcome.isFailure, s"hostname mismatch must cause handshake failure, got: $outcome")
                            }
                        }
                    }
                }
            yield result
        }
    }

end ConnectionUpgradeTlsTest
