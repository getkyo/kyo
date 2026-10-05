package kyo.internal.teams

import kyo.*
import kyo.TeamsLocal.*
import kyo.TeamsMalformedTokenException.Claim
import kyo.TeamsMalformedTokenException.Part
import kyo.TeamsMalformedTokenException.Problem
import kyo.crypto.Rsa

class JwtTest extends kyo.test.Test[Any]:

    // A local HttpServer's closed connections are reaped on the selector's next pass, which the socket leak check sees as an open
    // descriptor.
    override def config = super.config.leakCheckSockets(false)

    private def verify(
        local: TeamsLocal.Local,
        authorization: Maybe[String],
        config: TeamsConfig,
        body: Maybe[Span[Byte]] = Absent
    )(using Frame): Result[TeamsWebhookVerifyFailure, Unit] < Async =
        Teams.run(config)(Abort.run[TeamsWebhookVerifyFailure](
            Teams.Webhook.verify(authorization, body.getOrElse(local.activity()))
        ))

    /** Runs `test` on a local peer with the controlled clock at the instant the vendored tokens were issued. */
    private def atSigning[A](test: TeamsLocal.Local => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        Clock.withTimeControl(control => withLocal(local => control.set(signedAt).andThen(test(local))))

    /** Each authorization verified on a fresh client, with the number of metadata requests the peer saw. */
    private def refusals(local: TeamsLocal.Local, authorizations: Maybe[String]*)(using
        Frame
    ): (Chunk[Result[TeamsWebhookVerifyFailure, Unit]], Int) < Async =
        Kyo.foreach(Chunk.from(authorizations))(verify(local, _, local.config)).map(results =>
            local.seen("metadata").map(seen => (results, seen.size))
        )

    private def malformed(problem: Problem)(using Frame) = Result.fail(TeamsMalformedTokenException(problem))

    private def bearer(token: String): Maybe[String] = Present(s"Bearer $token")

    "a delivery with no Authorization header is refused as missing, before anything is fetched" in {
        withLocal { local =>
            refusals(local, Absent).map(result => assert(result == (Chunk(Result.fail(TeamsMissingAuthorizationException())), 0)))
        }
    }

    "the header must be the Bearer scheme, in any case, followed by one space" in {
        withLocal { local =>
            refusals(local, Present("Basic YTpi"), Present("Bearer"), Present("Bearera.b"), Present("bEaReR a.b")).map { result =>
                assert(result == (
                    Chunk(
                        malformed(Problem.NotBearer),
                        malformed(Problem.NotBearer),
                        malformed(Problem.NotBearer),
                        malformed(Problem.Segments(2))
                    ),
                    0
                ))
            }
        }
    }

    "a token longer than maxTokenLength is refused; one of exactly that length is read" in {
        withLocal { local =>
            val config = local.config.copy(maxTokenLength = 16)
            for
                over  <- verify(local, bearer("a" * 17), config)
                exact <- verify(local, bearer("a" * 16), config)
            yield assert((over, exact) == (malformed(Problem.TooLong(17, 16)), malformed(Problem.Segments(1))))
            end for
        }
    }

    "a token is three dot-separated segments" in {
        withLocal { local =>
            refusals(local, bearer(""), bearer("a.b"), bearer("a.b.c.d"), bearer("..")).map { result =>
                assert(result._1.take(3) == Chunk(
                    malformed(Problem.Segments(1)),
                    malformed(Problem.Segments(2)),
                    malformed(Problem.Segments(4))
                ))
                assert(
                    result._1(3) == malformed(Problem.Json(Part.Header, TeamsDecodeException.Failure.TruncatedInput)),
                    s"got ${result._1(3)}"
                )
                assert(result._2 == 0)
            }
        }
    }

    "each segment is canonical unpadded base64url" in {
        withLocal { local =>
            val header = segment("""{"alg":"RS256","kid":"k1"}""")
            val claims = segment(local.claims())
            refusals(
                local,
                bearer(s"a+b.$claims.AAAA"),
                bearer(s"$header.e30=.AAAA"),
                bearer(s"$header.$claims.A"),
                bearer(s"$header.$claims.AB")
            )
                .map { result =>
                    assert(result == (
                        Chunk(
                            malformed(Problem.Base64(Part.Header, Base64.Failure.IllegalCharacter(1))),
                            malformed(Problem.Base64(Part.Claims, Base64.Failure.UnexpectedPadding(3))),
                            malformed(Problem.Base64(Part.Signature, Base64.Failure.DanglingCharacter(1))),
                            malformed(Problem.Base64(Part.Signature, Base64.Failure.NonCanonicalTail))
                        ),
                        0
                    ))
                }
        }
    }

    "the header and the claims are JSON objects the module reads" in {
        withLocal { local =>
            refusals(
                local,
                bearer(unsigned(local.claims(), header = "not json")),
                bearer(unsigned(local.claims(), header = """{"kid":"k1"}""")),
                bearer(unsigned("[1,2]"))
            ).map { result =>
                assert(result == (
                    Chunk(
                        malformed(Problem.Json(Part.Header, TeamsDecodeException.Failure.Parse)),
                        malformed(Problem.Json(Part.Header, TeamsDecodeException.Failure.MissingField)),
                        malformed(Problem.Json(Part.Claims, TeamsDecodeException.Failure.TypeMismatch))
                    ),
                    0
                ))
            }
        }
    }

    "iss, aud, exp and serviceUrl are required, in that order; nbf is not" in {
        withLocal { local =>
            refusals(
                local,
                bearer(unsigned("{}")),
                bearer(unsigned(local.claims(iss = ""))),
                bearer(unsigned(local.claims(aud = ""))),
                bearer(unsigned(local.claims(exp = ""))),
                bearer(unsigned(local.claims(serviceUrl = ""))),
                bearer(unsigned(local.claims(nbf = "")))
            ).map { result =>
                assert(result == (
                    Chunk(
                        malformed(Problem.MissingClaim(Claim.Issuer)),
                        malformed(Problem.MissingClaim(Claim.Issuer)),
                        malformed(Problem.MissingClaim(Claim.Audience)),
                        malformed(Problem.MissingClaim(Claim.Expiry)),
                        malformed(Problem.MissingClaim(Claim.ServiceUrl)),
                        Result.fail(TeamsSignatureMismatchException())
                    ),
                    1
                ))
            }
        }
    }

    "aud is a string or an array of strings, and an array naming the bot among others is accepted" in {
        withLocal { local =>
            refusals(
                local,
                bearer(unsigned(local.claims(aud = "5"))),
                bearer(unsigned(local.claims(aud = s"""["other",5]"""))),
                bearer(unsigned(local.claims(aud = s"""["other","${appId.value}"]""")))
            ).map { result =>
                assert(result == (
                    Chunk(
                        malformed(Problem.Json(Part.Claims, TeamsDecodeException.Failure.NoVariantMatch)),
                        malformed(Problem.Json(Part.Claims, TeamsDecodeException.Failure.NoVariantMatch)),
                        Result.fail(TeamsSignatureMismatchException())
                    ),
                    1
                ))
            }
        }
    }

    "only RS256 is accepted, whatever the token asks: none, HMAC and RS512 are refused before a key is fetched" in {
        withLocal { local =>
            val claims = local.claims()
            refusals(
                local,
                bearer(unsigned(claims, header = """{"alg":"none","kid":"k1"}""")),
                bearer(unsigned(claims, header = """{"alg":"HS256","kid":"k1"}""")),
                bearer(unsigned(claims, header = """{"alg":"RS512","kid":"k1"}""")),
                bearer(unsigned(claims, header = s"""{"alg":"${"R" * 40}\\u0007","kid":"k1"}"""))
            ).map { result =>
                assert(result == (
                    Chunk(
                        Result.fail(TeamsUnsupportedAlgorithmException("none")),
                        Result.fail(TeamsUnsupportedAlgorithmException("HS256")),
                        Result.fail(TeamsUnsupportedAlgorithmException("RS512")),
                        Result.fail(TeamsUnsupportedAlgorithmException("R" * 16))
                    ),
                    0
                ))
            }
        }
    }

    "a token naming no kid is refused" in {
        withLocal { local =>
            refusals(local, bearer(unsigned(local.claims(), header = """{"alg":"RS256"}"""))).map { result =>
                assert(result == (Chunk(malformed(Problem.MissingKeyId)), 0))
            }
        }
    }

    "exp and nbf are NumericDates from 0 to the end of year 9999" in {
        withLocal { local =>
            refusals(
                local,
                bearer(unsigned(local.claims(exp = "-1"))),
                bearer(unsigned(local.claims(nbf = "253402300800"))),
                bearer(unsigned(local.claims(exp = "1.5")))
            ).map { result =>
                assert(
                    result == (
                        Chunk(
                            malformed(Problem.Json(Part.Claims, TeamsDecodeException.Failure.Range)),
                            malformed(Problem.Json(Part.Claims, TeamsDecodeException.Failure.Range)),
                            malformed(Problem.Json(Part.Claims, TeamsDecodeException.Failure.TypeMismatch))
                        ),
                        0
                    ),
                    s"got $result"
                )
            }
        }
    }

    "the issuer is the configured one, then the audience names the bot" in {
        withLocal { local =>
            val many = (1 to 10).map(i => s"\"a$i\"").mkString("[", ",", "]")
            refusals(
                local,
                bearer(unsigned(local.claims(iss = "\"https://login.example.com\"", aud = "\"other\""))),
                bearer(unsigned(local.claims(aud = "\"other\""))),
                bearer(unsigned(local.claims(aud = many)))
            ).map { result =>
                assert(result == (
                    Chunk(
                        Result.fail(TeamsWrongIssuerException("https://login.example.com")),
                        Result.fail(TeamsWrongAudienceException(Chunk("other"))),
                        Result.fail(TeamsWrongAudienceException(Chunk.from((1 to 8).map(i => s"a$i"))))
                    ),
                    0
                ))
            }
        }
    }

    "a token is current from nbf minus the skew until exp plus the skew, exactly" in {
        Clock.withTimeControl { control =>
            withLocal { local =>
                val token             = bearer(unsigned(local.claims(nbf = "1000", exp = "3600")))
                val skew              = local.config.clockSkew
                def at(seconds: Long) =
                    control.set(Instant.Epoch + seconds.seconds).andThen(verify(local, token, local.config))
                for
                    early <- at(699)
                    from  <- at(700)
                    last  <- at(3899)
                    after <- at(3900)
                yield assert(Chunk(early, from, last, after) == Chunk(
                    Result.fail(TeamsTokenNotYetValidException(Instant.Epoch + 1000.seconds, Instant.Epoch + 699.seconds, skew)),
                    Result.fail(TeamsSignatureMismatchException()),
                    Result.fail(TeamsSignatureMismatchException()),
                    Result.fail(TeamsTokenExpiredException(Instant.Epoch + 3600.seconds, Instant.Epoch + 3900.seconds, skew))
                ))
                end for
            }
        }
    }

    "the signing key must be an RSA key kyo-crypto accepts, and the signature must verify under it" in {
        val even = Base64.encodeUrl(Span.from(Array.tabulate[Byte](256)(i => if i == 0 then 0x80.toByte else 0)))
        withLocal { local =>
            local.reply("jwks", keySet(key("k1"), key("even", n = even), key("bad", n = "a+b"))).andThen {
                refusals(
                    local,
                    bearer(unsigned(local.claims())),
                    bearer(unsigned(local.claims(), header = """{"alg":"RS256","kid":"even"}""")),
                    bearer(unsigned(local.claims(), header = """{"alg":"RS256","kid":"bad"}"""))
                ).map { result =>
                    assert(result._1 == Chunk(
                        Result.fail(TeamsSignatureMismatchException()),
                        Result.fail(TeamsInvalidKeyException("even", Rsa.KeyFailure.Bounds(Rsa.BoundsFailure.ModulusEven))),
                        Result.fail(TeamsInvalidKeyException("bad", Rsa.KeyFailure.NotBase64Url(Rsa.Component.Modulus)))
                    ))
                }
            }
        }
    }

    "a token signed by the key its kid names verifies; a changed claim, or a signature by another key, does not" in {
        atSigning { local =>
            val body    = Present(local.activity(serviceUrl = signedServiceUrl))
            val parts   = signed.split('.')
            val changed = Chunk(parts(0), segment(signedClaims.replace("253402300799", "253402300798")), parts(2)).mkString(".")
            Kyo.foreach(Chunk(signed, changed, signedForOtherKid))(token => verify(local, bearer(token), local.config, body)).map {
                results =>
                    assert(results == Chunk(
                        Result.unit,
                        Result.fail(TeamsSignatureMismatchException()),
                        Result.fail(TeamsSignatureMismatchException())
                    ))
            }
        }
    }

    "the body is read only under a verified signature" in {
        atSigning { local =>
            val noServiceUrl = Present(kyo.internal.charset.Utf8.encode("""{"channelId":"msteams"}"""))
            for
                unverified <- verify(local, bearer(signedForOtherKid), local.config, noServiceUrl)
                verified   <- verify(local, bearer(signed), local.config, noServiceUrl)
            yield assert(
                (unverified, verified) == (
                    Result.fail(TeamsSignatureMismatchException()),
                    Result.fail(TeamsWebhookDecodeException(TeamsDecodeException.Failure.MissingField, Chunk("serviceUrl"), Absent))
                ),
                s"got: ${verified.failure.map(fieldsOf)}"
            )
            end for
        }
    }

    "a verified token binds the body: the key endorses its channel, which is Teams, and its service URL is the token's claim" in {
        atSigning { local =>
            val token                = bearer(signed)
            val otherUrl             = "https://smba.trafficmanager.net/emea/"
            val webChatToo           = keySet(key("s1", n = signedModulus, endorsements = "\"msteams\",\"webchat\""))
            def at(body: Span[Byte]) = verify(local, token, local.config, Present(body))
            for
                unendorsed <- at(local.activity(channelId = "webchat", serviceUrl = otherUrl))
                misbound   <- at(local.activity(serviceUrl = otherUrl))
                _          <- local.reply("jwks", webChatToo)
                webChat    <- at(local.activity(channelId = "webchat", serviceUrl = signedServiceUrl))
                teams      <- at(local.activity(serviceUrl = signedServiceUrl))
            yield assert(Chunk(unendorsed, misbound, webChat, teams) == Chunk(
                Result.fail(TeamsMissingEndorsementException("webchat")),
                Result.fail(TeamsServiceUrlMismatchException()),
                Result.fail(TeamsUnsupportedChannelException("webchat")),
                Result.unit
            ))
            end for
        }
    }

end JwtTest
