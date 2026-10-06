package kyo.internal.teams

import kyo.*
import kyo.TeamsLocal.*

class TokenCacheTest extends kyo.test.Test[Any]:

    // A local HttpServer's closed connections are reaped on the selector's next pass, which the socket leak check sees as an open
    // descriptor.
    override def config = super.config.leakCheckSockets(false)

    private val hello = Teams.Message.Create.text("hello")

    private val clientId = "00001111-aaaa-2222-bbbb-3333cccc4444"
    private val scope    = "https%3A%2F%2Fapi.botframework.com%2F.default"

    private def send(local: TeamsLocal.Local, teams: Teams)(using Frame): Result[TeamsSendFailure, Teams.ActivityId] < Async =
        Abort.run[TeamsSendFailure](Env.run(teams)(Teams.send(local.reference, hello)))

    private def token(tokenType: String, expiresIn: String): Reply =
        json(s"""{"token_type":"$tokenType","expires_in":$expiresIn,"access_token":"$tokenText"}""")

    "a client secret is sent as client credentials to the multi-tenant authority" in {
        withLocal { local =>
            local.reply("send", resource("1:a")).andThen(local.api(Teams.send(local.reference, hello))).andThen {
                local.seen("token").map { seen =>
                    assert(seen.map(s => (s.method, s.path, s.contentType, s.authorization, s.body)) == Chunk((
                        "POST",
                        "/botframework.com/oauth2/v2.0/token",
                        Present("application/x-www-form-urlencoded"),
                        Absent,
                        s"grant_type=client_credentials&client_id=$clientId&scope=$scope&client_secret=$secretText"
                    )))
                }
            }
        }
    }

    "a single-tenant registration asks its own tenant's authority" in {
        withLocal { local =>
            val tenant = Teams.TenantId.init("aaaabbbb-0000-cccc-1111-dddd2222eeee").getOrThrow
            val config = local.config.copy(tenant = TeamsConfig.Tenant.SingleTenant(tenant))
            local.reply("send", resource("1:a")).andThen(Teams.run(config)(Teams.send(local.reference, hello))).andThen {
                local.seen("token").map(seen =>
                    assert(seen.map(_.path) == Chunk("/aaaabbbb-0000-cccc-1111-dddd2222eeee/oauth2/v2.0/token"))
                )
            }
        }
    }

    "a federated credential sends the assertion its computation produces, evaluated for each fetch" in {
        withLocal { local =>
            AtomicInt.init.map { produced =>
                val assertion = produced.incrementAndGet.map(n => Teams.ClientAssertion.init(s"eyJh.eyJi.c2ln$n").getOrThrow)
                val config    = local.configWith(TeamsConfig.Credential.Federated(assertion)).copy(tokenRefreshMargin = 3600.seconds)
                local.reply("send", resource("1:a")).andThen {
                    Teams.run(config)(Teams.send(local.reference, hello).andThen(Teams.send(local.reference, hello)))
                }.andThen {
                    local.seen("token").map { seen =>
                        val prefix = s"grant_type=client_credentials&client_id=$clientId&scope=$scope" +
                            "&client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer&client_assertion="
                        assert(seen.map(_.body) == Chunk(s"${prefix}eyJh.eyJi.c2ln1", s"${prefix}eyJh.eyJi.c2ln2"))
                    }
                }
            }
        }
    }

    "a federated assertion's failure reaches the caller as it is, and nothing is requested" in {
        withLocal { local =>
            val config = local.configWith(TeamsConfig.Credential.Federated(Abort.fail(TeamsCredentialException("no workload token"))))
            Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))).map { result =>
                local.seen.map { seen =>
                    assert(result == Result.fail(TeamsCredentialException("no workload token")))
                    assert(result.failure.map(_.getMessage).exists(
                        _.contains("The federated credential's assertion could not be obtained: no workload token.")
                    ))
                    assert(seen == Chunk.empty)
                }
            }
        }
    }

    "a federated assertion's throwable cause is the leaf's cause, and only its class name is in the message" in {
        withLocal { local =>
            val cause  = new IllegalStateException(s"workload token $secretText expired")
            val config = local.configWith(TeamsConfig.Credential.Federated(Abort.fail(TeamsCredentialException(cause))))
            Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))).map { result =>
                local.seen.map { seen =>
                    assert(result == Result.fail(TeamsCredentialException(cause)))
                    assert(result.failure.map(_.getCause) == Present(cause))
                    assert(result.failure.map(_.getMessage).exists(
                        _.contains("The federated credential's assertion could not be obtained: IllegalStateException.")
                    ))
                    assert(!result.failure.exists(e => e.getMessage.contains(secretText) || e.toString.contains(secretText)))
                    assert(seen == Chunk.empty)
                }
            }
        }
    }

    "a federated assertion's text cause is bounded to 200 printable ASCII characters in the message" in {
        withLocal { local =>
            val text   = "\u0001" + "x" * 300
            val config = local.configWith(TeamsConfig.Credential.Federated(Abort.fail(TeamsCredentialException(text))))
            Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))).map { result =>
                assert(result == Result.fail(TeamsCredentialException(text)))
                assert(result.failure.map(_.getMessage).exists(
                    _.contains("The federated credential's assertion could not be obtained: ?" + "x" * 199 + ".")
                ))
                assert(!result.failure.exists(_.getMessage.contains("x" * 200)))
                assert(result.failure.map(e => Maybe(e.getCause)) == Present(Absent))
            }
        }
    }

    "a managed identity asks its endpoint for the resource with the identity header, and reuses the token until expires_on" in {
        Clock.withTimeControl { control =>
            withLocal { local =>
                val header   = Teams.IdentityHeader.init(Chunk("local", "identity", "header").mkString("-")).getOrThrow
                val endpoint = local.base.copy(path = "/MSI/token")
                val config   = local.configWith(TeamsConfig.Credential.ManagedIdentity(endpoint, header))
                local.reply("msi", json(s"""{"token_type":"Bearer","expires_on":"3600","access_token":"$tokenText"}"""))
                    .andThen(local.reply("send", resource("1:a")))
                    .andThen {
                        Teams.init(config).map { teams =>
                            val sendOnce = Abort.run[TeamsSendFailure](Env.run(teams)(Teams.send(local.reference, hello)))
                            for
                                first  <- sendOnce
                                _      <- control.advance(3299.seconds)
                                second <- sendOnce
                                before <- local.seen("msi")
                                _      <- control.advance(1.second)
                                third  <- sendOnce
                                after  <- local.seen("msi")
                                sends  <- local.seen("send")
                            yield
                                assert(Chunk(first, second, third).forall(_.isSuccess))
                                assert((before.size, after.size) == (1, 2))
                                assert(before.map(s =>
                                    (
                                        s.method,
                                        s.path,
                                        s.identityHeader,
                                        s.query.map(q => HttpUrl.fromUri("/?" + q)).map(u =>
                                            (u.query("resource"), u.query("api-version"), u.query("client_id"))
                                        )
                                    )
                                ) == Chunk((
                                    "GET",
                                    "/MSI/token",
                                    Present(header.value),
                                    Present((Present("https://api.botframework.com"), Present("2019-08-01"), Present(clientId)))
                                )))
                                assert(sends.map(_.authorization) == Chunk.fill(3)(Present(s"Bearer $tokenText")))
                            end for
                        }
                    }
            }
        }
    }

    "concurrent callers start a fetch only for a call that waits on it" in {
        // Every evaluation of the assertion fails with its own number, and a caller fails with the number of the fetch it waited on,
        // so a number no caller saw is a fetch started by a caller that lost the race to install it. The race needs the scheduler to
        // preempt a caller between reading the state and installing its fetch while another carrier runs, which the spinning fibers
        // provoke. Measured on a cache that starts its fetch before installing it: on JVM 3 to 18 such fetches per 20000 rounds, 12
        // runs out of 12 red, about 5 s; on Native 0 to 4 per 1000 rounds at about 5.6 ms a round, so it runs 2000 to stay well inside
        // the time limit. On a single carrier (JS, Wasm) every round waits out each spinner's time slice, 4.7 s a round on JS, and
        // 20000 rounds without spinners found none, so there the rounds only check the invariant.
        val parallel = Runtime.getRuntime.availableProcessors() > 1
        val rounds   = if kyo.internal.Platform.isJVM then 20000 else if parallel then 2000 else 200
        withLocal { local =>
            AtomicInt.init.map { evaluated =>
                val assertion = evaluated.incrementAndGet.map(n => Abort.fail(TeamsCredentialException(n.toString)))
                val config    = local.configWith(TeamsConfig.Credential.Federated(assertion))
                Teams.init(config).map { teams =>
                    AtomicBoolean.init(false).map { stop =>
                        def spin: Unit < Sync  = stop.get.map(s => if s then Kyo.unit else spin)
                        def round: Int < Async =
                            evaluated.set(0).andThen(TokenCache.init(config, teams.http)).map { cache =>
                                Latch.init(1).map { go =>
                                    Kyo.foreach(Chunk.range(0, 32))(_ => Fiber.initUnscoped(go.await.andThen(Abort.run(cache.get)))).map {
                                        callers =>
                                            go.release.andThen(Kyo.foreach(callers)(_.get)).map { results =>
                                                val waited = results.collect {
                                                    case Result.Failure(TeamsCredentialException(n: String)) => n.toInt
                                                }.toSet
                                                evaluated.get.map(n => Chunk.range(1, n + 1).count(i => !waited.contains(i)))
                                            }
                                    }
                                }
                            }
                        Kyo.foreach(Chunk.range(0, if parallel then 16 else 0))(_ => Fiber.initUnscoped(spin)).map { spinners =>
                            Kyo.foreach(Chunk.range(0, rounds))(_ => round).map { unwaited =>
                                stop.set(true).andThen(Kyo.foreach(spinners)(_.get)).andThen {
                                    assert(unwaited.sum == 0, s"${unwaited.sum} fetches started with no caller waiting on them")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "a burst of calls requests the token once" in {
        withLocal { local =>
            local.reply("token").andThen(local.reply("send", resource("1:a"))).andThen {
                Teams.init(local.config).map { teams =>
                    Kyo.foreach(Chunk.range(0, 5))(_ => Fiber.initUnscoped(send(local, teams))).map { fibers =>
                        local.held.await
                            .andThen(local.reply("token", bearer(tokenText, 3600)))
                            .andThen(local.release)
                            .andThen(Kyo.foreach(fibers)(_.get))
                            .map { results =>
                                local.seen.map { seen =>
                                    assert(results == Chunk.fill(5)(Result.succeed(Teams.ActivityId.init("1:a").getOrThrow)))
                                    val counts = (seen.count(_.label == "token"), seen.count(_.label == "send"))
                                    assert(counts == (1, 5), s"(token, send) requests: $counts")
                                }
                            }
                    }
                }
            }
        }
    }

    "the token is reused until tokenRefreshMargin before it expires, then fetched again and sent" in {
        Clock.withTimeControl { control =>
            withLocal { local =>
                val fresh = tokenText + "2"
                local.reply("token", bearer(tokenText, 3600), bearer(fresh, 3600)).andThen(local.reply("send", resource("1:a"))).andThen {
                    Teams.init(local.config).map { teams =>
                        for
                            _      <- send(local, teams)
                            _      <- control.advance(3299.seconds)
                            _      <- send(local, teams)
                            before <- local.seen("token")
                            _      <- control.advance(1.second)
                            _      <- send(local, teams)
                            after  <- local.seen("token")
                            sends  <- local.seen("send")
                        yield
                            assert((before.size, after.size) == (1, 2))
                            assert(sends.map(_.authorization) ==
                                Chunk(Present(s"Bearer $tokenText"), Present(s"Bearer $tokenText"), Present(s"Bearer $fresh")))
                        end for
                    }
                }
            }
        }
    }

    "a failed fetch caches nothing: the next call fetches again" in {
        withLocal { local =>
            local.reply("token", Reply(HttpStatus(401), """{"error":"invalid_client"}"""), bearer(tokenText, 3600))
                .andThen(local.reply("send", resource("1:a")))
                .andThen {
                    Teams.init(local.config).map { teams =>
                        send(local, teams).map(first => send(local, teams).map(first -> _)).map { (first, second) =>
                            local.seen("token").map { tokens =>
                                assert(first == Result.fail(TeamsTokenRejectedException(
                                    TeamsTokenRejectedException.Code.InvalidClient,
                                    Chunk.empty,
                                    Absent,
                                    Absent
                                )))
                                assert(second == Result.succeed(Teams.ActivityId.init("1:a").getOrThrow))
                                assert(tokens.size == 2)
                            }
                        }
                    }
                }
        }
    }

    "a token fetch is never retried, whatever the config's retry" in {
        withLocal { local =>
            local.reply("token", Reply(HttpStatus(503), "")).andThen {
                val config = local.config.copy(retry = Present(Schedule.fixed(Duration.Zero).take(3)))
                Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))).map { result =>
                    local.seen.map { seen =>
                        assert(result == Result.fail(TeamsUnexpectedStatusException(TokenCache.IdentityPlatformMethod, HttpStatus(503))))
                        assert(seen.map(_.label) == Chunk("token"))
                    }
                }
            }
        }
    }

    "each answer of the identity platform the module cannot use is its own leaf, keeping nothing of the request" in {
        val described = s"AADSTS7000215: Invalid client secret provided: $secretText"
        val cases     = Chunk(
            Reply(
                HttpStatus(401),
                s"""{"error":"invalid_client","error_description":"$described","error_codes":[7000215],"trace_id":"t-1","correlation_id":"c-1"}"""
            ) ->
                TeamsTokenRejectedException(TeamsTokenRejectedException.Code.InvalidClient, Chunk(7000215), Present("t-1"), Present("c-1")),
            Reply(HttpStatus(400), """{"error":"interaction_required"}""") ->
                TeamsTokenRejectedException(TeamsTokenRejectedException.Code.Other("interaction_required"), Chunk.empty, Absent, Absent),
            Reply(HttpStatus(429), "", Chunk("Retry-After" -> "30")) ->
                TeamsRateLimitException(TokenCache.IdentityPlatformMethod, Present(30.seconds), Absent, Absent),
            Reply(HttpStatus(500), """{"error":"temporarily_unavailable"}""") ->
                TeamsUnexpectedStatusException(TokenCache.IdentityPlatformMethod, HttpStatus(500)),
            token("pop", "3600") -> TeamsUnsupportedTokenTypeException("pop"),
            token("Bearer", "0") -> TeamsDecodeException(
                TokenCache.IdentityPlatformMethod,
                TeamsDecodeException.Part.Token,
                TeamsDecodeException.Failure.Range,
                Chunk("expires_in"),
                Absent
            ),
            json(s"""{"token_type":"Bearer","access_token":"$tokenText"}""") -> TeamsDecodeException(
                TokenCache.IdentityPlatformMethod,
                TeamsDecodeException.Part.Token,
                TeamsDecodeException.Failure.MissingField,
                Chunk("expires_in"),
                Absent
            )
        )
        withLocal { local =>
            Kyo.foreach(cases) { (answer, _) =>
                local.reply("token", answer).andThen(local.api(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))))
            }.map { results =>
                assert(results == cases.map((_, leaf) => Result.fail(leaf)), s"got: ${results.map(_.failure.map(fieldsOf))}")
                assert(!results.flatMap(_.failure.toChunk).exists(e => rendered(e).contains(secretText) || rendered(e).contains(tokenText)))
            }
        }
    }

    "each answer of the managed identity endpoint the module cannot use is its own leaf" in {
        withLocal { local =>
            val header = Teams.IdentityHeader.init("h").getOrThrow
            val config = local.configWith(TeamsConfig.Credential.ManagedIdentity(local.base.copy(path = "/MSI/token"), header))
            val cases  = Chunk(
                json(s"""{"token_type":"Bearer","expires_on":"soon","access_token":"$tokenText"}""") -> TeamsDecodeException(
                    TokenCache.ManagedIdentityMethod,
                    TeamsDecodeException.Part.Token,
                    TeamsDecodeException.Failure.Range,
                    Chunk("expires_on"),
                    Absent
                ),
                Reply(HttpStatus(400), """{"error":"invalid_resource"}""") ->
                    TeamsUnexpectedStatusException(TokenCache.ManagedIdentityMethod, HttpStatus(400)),
                Reply(HttpStatus(429), "", Chunk("Retry-After" -> "5")) ->
                    TeamsRateLimitException(TokenCache.ManagedIdentityMethod, Present(5.seconds), Absent, Absent)
            )
            Kyo.foreach(cases) { (answer, _) =>
                local.reply("msi", answer).andThen(Teams.run(config)(Abort.run[TeamsSendFailure](Teams.send(local.reference, hello))))
            }.map(results => assert(results == cases.map((_, leaf) => Result.fail(leaf))))
        }
    }

end TokenCacheTest
