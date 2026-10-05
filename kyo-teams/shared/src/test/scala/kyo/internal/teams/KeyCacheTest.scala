package kyo.internal.teams

import kyo.*
import kyo.TeamsLocal.*

class KeyCacheTest extends kyo.test.Test[Any]:

    // A local HttpServer's closed connections are reaped on the selector's next pass, which the socket leak check sees as an open
    // descriptor.
    override def config = super.config.leakCheckSockets(false)

    private val mismatch = Result.fail(TeamsSignatureMismatchException())

    private def header(kid: String): String = s"""{"alg":"RS256","kid":"$kid"}"""

    /** Verifies a token naming `kid` on `teams`, a client kept across calls so its key cache is. */
    private def verifyOn(local: TeamsLocal.Local, teams: Teams, kid: String)(using
        Frame
    ): Result[TeamsWebhookVerifyFailure, Unit] < Async =
        val authorization = Present(s"Bearer ${unsigned(local.claims(exp = "999999"), header(kid))}")
        Abort.run[TeamsWebhookVerifyFailure](Env.run(teams)(Teams.Webhook.verify(authorization, local.activity())))
    end verifyOn

    private def fetches(local: TeamsLocal.Local)(using Frame): (Int, Int) < Sync =
        local.seen.map(seen => (seen.count(_.label == "metadata"), seen.count(_.label == "jwks")))

    "the metadata names the key set, and both are fetched once and reused" in {
        withLocal { local =>
            Teams.init(local.config).map { teams =>
                for
                    first  <- verifyOn(local, teams, "k1")
                    second <- verifyOn(local, teams, "k1")
                    counts <- fetches(local)
                    seen   <- local.seen
                yield
                    assert((first, second, counts) == (mismatch, mismatch, (1, 1)))
                    assert(seen.map(s => (s.method, s.path)) ==
                        Chunk(("GET", "/v1/.well-known/openidconfiguration"), ("GET", "/v1/.well-known/keys")))
                end for
            }
        }
    }

    "an unknown kid refetches the set once keysMinRefresh has passed since the last fetch, and not before" in {
        Clock.withTimeControl { control =>
            withLocal { local =>
                Teams.init(local.config).map { teams =>
                    for
                        initial <- verifyOn(local, teams, "k2")
                        _       <- control.advance(3599.seconds)
                        early   <- verifyOn(local, teams, "k2")
                        before  <- fetches(local)
                        _       <- local.reply("jwks", keySet(key("k1"), key("k2")))
                        _       <- control.advance(1.second)
                        rotated <- verifyOn(local, teams, "k2")
                        after   <- fetches(local)
                    yield
                        assert(Chunk(initial, early, rotated) ==
                            Chunk(Result.fail(TeamsUnknownKeyException("k2")), Result.fail(TeamsUnknownKeyException("k2")), mismatch))
                        assert((before, after) == ((1, 1), (2, 2)))
                    end for
                }
            }
        }
    }

    "a known kid is used until keysMaxAge, then the set is fetched again and a removed key is unknown" in {
        Clock.withTimeControl { control =>
            withLocal { local =>
                Teams.init(local.config).map { teams =>
                    for
                        fresh   <- verifyOn(local, teams, "k1")
                        _       <- local.reply("jwks", keySet(key("k2")))
                        _       <- control.advance((24 * 3600 - 1).seconds)
                        aged    <- verifyOn(local, teams, "k1")
                        before  <- fetches(local)
                        _       <- control.advance(1.second)
                        removed <- verifyOn(local, teams, "k1")
                        after   <- fetches(local)
                    yield
                        assert(Chunk(fresh, aged, removed) == Chunk(mismatch, mismatch, Result.fail(TeamsUnknownKeyException("k1"))))
                        assert((before, after) == ((1, 1), (2, 2)))
                    end for
                }
            }
        }
    }

    "concurrent verifications share one fetch" in {
        withLocal { local =>
            local.reply("jwks").andThen {
                Teams.init(local.config).map { teams =>
                    Kyo.foreach(Chunk.range(0, 5))(i => Fiber.initUnscoped(verifyOn(local, teams, if i % 2 == 0 then "k1" else "k9")))
                        .map { fibers =>
                            local.held.await
                                .andThen(local.reply("jwks", keySet(key("k1"))))
                                .andThen(local.release)
                                .andThen(Kyo.foreach(fibers)(_.get))
                                .map { results =>
                                    fetches(local).map { counts =>
                                        val unknown = Result.fail(TeamsUnknownKeyException("k9"))
                                        assert(results == Chunk(mismatch, unknown, mismatch, unknown, mismatch))
                                        assert(counts == (1, 1))
                                    }
                                }
                        }
                }
            }
        }
    }

    // Every lookup is held after reading the empty cache and before claiming the fetch, the interleaving under which lookups that start
    // their fetch before the claim each send a request. The fetches started while all are held must be none; the peer is then given every
    // request those fetches sent before the lookups go on, so the count at the end does not depend on how fast an interrupt lands.
    "lookups that read the empty cache together start one fetch" in {
        withLocal { local =>
            for
                teams   <- Teams.init(local.config)
                arrived <- Latch.init(5)
                open    <- Latch.init(1)
                started <- AtomicInt.init
                keys    <- KeyCache.init(
                    local.config,
                    teams.http,
                    KeyCache.Hooks(arrived.release.andThen(open.await), started.incrementAndGet.unit)
                )
                fibers  <- Kyo.foreach(Chunk.range(0, 5))(_ => Fiber.initUnscoped(Abort.run(keys.get("k1"))))
                _       <- arrived.await
                early   <- started.get
                _       <- assertEventually(fetches(local).map(_._1 == early))
                _       <- open.release
                results <- Kyo.foreach(fibers)(_.get)
                counts  <- fetches(local)
            yield
                assert(
                    (early, counts) == (0, (1, 1)),
                    s"fetches started before any lookup claimed one: $early, metadata and key set fetches: $counts"
                )
                assert(results.map(_.map(_.kid)) == Chunk.fill(5)(Result.succeed(Present("k1"))))
            end for
        }
    }

    "a lookup interrupted while the fetch is in flight leaves the fetch to the next lookup" in {
        withLocal { local =>
            local.reply("metadata").andThen {
                Teams.init(local.config).map { teams =>
                    for
                        first  <- Fiber.initUnscoped(verifyOn(local, teams, "k1"))
                        _      <- local.held.await
                        _      <- first.interrupt
                        second <- Fiber.initUnscoped(verifyOn(local, teams, "k1"))
                        _      <- local.reply("metadata", local.metadata())
                        _      <- local.release
                        result <- second.get
                        counts <- fetches(local)
                    yield assert((result, counts) == (mismatch, (1, 1)), s"result: $result, counts: $counts")
                    end for
                }
            }
        }
    }

    "keys the module cannot use are dropped: another kty, another use, no n or e, and a repeated kid keeps the first" in {
        withLocal { local =>
            val even = Base64.encodeUrl(Span.from(Array.tabulate[Byte](256)(i => if i == 0 then 0x80.toByte else 0)))
            local.reply(
                "jwks",
                keySet(
                    key("ec", kty = "EC"),
                    key("enc", use = "\"enc\""),
                    s"""{"kty":"RSA","kid":"bare"}""",
                    key("nouse", use = "null"),
                    key("twice"),
                    key("twice", n = even)
                )
            ).andThen {
                Teams.init(local.config).map { teams =>
                    Kyo.foreach(Chunk("ec", "enc", "bare", "nouse", "twice"))(verifyOn(local, teams, _)).map { results =>
                        assert(results == Chunk(
                            Result.fail(TeamsUnknownKeyException("ec")),
                            Result.fail(TeamsUnknownKeyException("enc")),
                            Result.fail(TeamsUnknownKeyException("bare")),
                            mismatch,
                            mismatch
                        ))
                    }
                }
            }
        }
    }

    "each metadata or key set the module cannot use is its own leaf, and a failed fetch is not cached" in {
        withLocal { local =>
            val cases = Chunk(
                local.metadata(issuer = "https://login.example.com")     -> TeamsWrongIssuerException("https://login.example.com"),
                local.metadata(algorithms = "\"RS512\",\"ES256\"")       -> TeamsMetadataAlgorithmException(Chunk("RS512", "ES256")),
                local.metadata(jwksUri = "http://keys.example.com/keys") -> TeamsRefusedUrlException(KeyCache.KeysMethod),
                local.metadata(jwksUri = "ftp://keys.example.com/keys")  -> TeamsRefusedUrlException(KeyCache.KeysMethod),
                Reply(HttpStatus(500), "") -> TeamsUnexpectedStatusException(KeyCache.MetadataMethod, HttpStatus(500)),
                json("""{"issuer":"x"}""") -> TeamsDecodeException(
                    KeyCache.MetadataMethod,
                    TeamsDecodeException.Part.Metadata,
                    TeamsDecodeException.Failure.MissingField,
                    Chunk("jwks_uri"),
                    Absent
                )
            )
            Teams.init(local.config).map { teams =>
                Kyo.foreach(cases)((metadata, _) => local.reply("metadata", metadata).andThen(verifyOn(local, teams, "k1"))).map {
                    results =>
                        local.reply("metadata", local.metadata()).andThen(verifyOn(local, teams, "k1")).map { recovered =>
                            fetches(local).map { counts =>
                                assert(
                                    results == cases.map((_, leaf) => Result.fail(leaf)),
                                    s"got: ${results.map(_.failure.map(fieldsOf))}"
                                )
                                assert(recovered == mismatch)
                                assert(counts == (7, 1))
                            }
                        }
                }
            }
        }
    }

    "a key set that is not JSON, that the peer refuses, or that exceeds keysMaxResponseLength is its own leaf" in {
        withLocal { local =>
            val big = keySet((1 to 40).map(i => key(s"k$i"))*)
            Teams.init(local.config).map { first =>
                Teams.init(local.config).map { second =>
                    Teams.init(local.config.copy(keysMaxResponseLength = 4.kib)).map { small =>
                        for
                            broken  <- local.reply("jwks", json("not json")).andThen(verifyOn(local, first, "k1"))
                            refused <- local.reply("jwks", Reply(HttpStatus(404), "")).andThen(verifyOn(local, second, "k1"))
                            large   <- local.reply("jwks", big).andThen(verifyOn(local, small, "k1"))
                        yield
                            assert(
                                broken == Result.fail(TeamsDecodeException(
                                    KeyCache.KeysMethod,
                                    TeamsDecodeException.Part.Keys,
                                    TeamsDecodeException.Failure.Parse,
                                    Chunk.empty,
                                    Present(0)
                                )),
                                s"got: ${broken.failure.map(fieldsOf)}"
                            )
                            assert(refused == Result.fail(TeamsUnexpectedStatusException(KeyCache.KeysMethod, HttpStatus(404))))
                            assert(large == Result.fail(TeamsTransportException(
                                KeyCache.KeysMethod,
                                TeamsTransportException.Kind.PayloadTooLarge(big.body.length.bytes, 4.kib),
                                "127.0.0.1",
                                local.base.port,
                                Absent
                            )()))
                        end for
                    }
                }
            }
        }
    }

end KeyCacheTest
