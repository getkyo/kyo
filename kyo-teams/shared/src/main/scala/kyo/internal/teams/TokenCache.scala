package kyo.internal.teams

import kyo.*

/** The Bot Connector access token, sent in the `Authorization` header of a Bot Connector call and nowhere else. */
final private[kyo] class AccessToken(val value: String):
    override def toString: String = "AccessToken(<redacted>)"

/** The outbound token of one client: obtained with the config's credential, reused until `tokenRefreshMargin` before it expires, and
  * fetched once for concurrent callers.
  *
  * The fetch runs on a fiber of its own, so a caller interrupted while it waits does not leave the others waiting on a fetch that
  * stopped. A fetch that fails leaves nothing cached: every caller that waited on it fails with its failure, and the next call fetches
  * again.
  */
final private[kyo] class TokenCache private (config: TeamsConfig, http: HttpClient, state: AtomicRef[TokenCache.State]):
    import TokenCache.*

    /** The current token, fetched when there is none or it is within the refresh margin of its expiry. */
    def get(using Frame): AccessToken < (Async & Abort[Failure]) =
        Clock.now.map { now =>
            state.get.map {
                case Ready(token, expiresAt) if now < expiresAt - config.tokenRefreshMargin => token
                case current: Fetching => current.fiber.getResult.map(result => settle(current, result))
                case current           =>
                    Async.uninterruptible(claim(current)).map {
                        case Present(fetching) => fetching.fiber.getResult.map(result => settle(fetching, result))
                        case Absent            => get
                    }
            }
        }

    // The fetch fiber waits on `go` until this caller has won the state, so a caller that loses the race never starts a request. The
    // claim runs where the caller's interrupt cannot reach: a won claim always opens `go`, or the callers waiting on the fiber would
    // wait forever.
    private def claim(current: State)(using Frame): Maybe[Fetching] < Async =
        Promise.init[Unit, Any].map { go =>
            Fiber.initUnscoped[Failure, Fetched, Any, Any](go.get.andThen(fetch)).map { fiber =>
                val fetching = new Fetching(fiber)
                state.compareAndSet(current, fetching).map { won =>
                    if won then go.completeUnitDiscard.andThen(Present(fetching))
                    else fiber.interrupt.andThen(Absent)
                }
            }
        }

    private def settle(fetching: Fetching, result: Result[Failure, Fetched])(using
        Frame
    ): AccessToken < (Sync & Abort[Failure]) =
        result match
            case Result.Success(fetched) => state.compareAndSet(fetching, Ready(fetched.token, fetched.expiresAt)).andThen(fetched.token)
            case Result.Failure(failure) => state.compareAndSet(fetching, Empty).andThen(Abort.fail(failure))
            case Result.Panic(ex)        => state.compareAndSet(fetching, Empty).andThen(Abort.panic(ex))

    private def fetch(using Frame): Fetched < (Async & Abort[Failure]) =
        config.credential match
            case TeamsConfig.Credential.Secret(secret) =>
                identityPlatform(Chunk("client_secret" -> secret.value))
            case TeamsConfig.Credential.Federated(assertion) =>
                // The caller's own function runs here, outside the module's transport and decode regions, so the leaf it fails with
                // reaches the caller as it is.
                assertion.map(a =>
                    identityPlatform(Chunk("client_assertion_type" -> JwtBearer, "client_assertion" -> a.value))
                )
            case TeamsConfig.Credential.ManagedIdentity(endpoint, header) =>
                managedIdentity(endpoint, header)

    private def identityPlatform(credential: Chunk[(String, String)])(using Frame): Fetched < (Async & Abort[Failure]) =
        val tenant = config.tenant match
            case TeamsConfig.Tenant.MultiTenant          => "botframework.com"
            case TeamsConfig.Tenant.SingleTenant(tenant) => tenant.value
        val url  = ServiceUrls.join(config.loginUrl, Chunk(tenant, "oauth2", "v2.0", "token"))
        val form = (Chunk("grant_type" -> "client_credentials", "client_id" -> config.appId.value, "scope" -> config.scope) ++ credential)
            .map((k, v) => s"${Form.encode(k)}=${Form.encode(v)}").mkString("&")
        Clock.now.map { requested =>
            Connector.transport(config, http, IdentityPlatformMethod, url, config.requestTimeout)(
                HttpClient.postTextResponse(
                    url,
                    form,
                    headers = HttpHeaders.empty.add("Content-Type", "application/x-www-form-urlencoded"),
                    failOnError = false
                )
            ).map { response =>
                val status = response.status
                if status.isSuccess then
                    decode[Wire.TokenResponse](IdentityPlatformMethod, response.fields.body).map { answer =>
                        checkType(answer.tokenType).andThen {
                            if answer.expiresIn <= 0 then
                                Abort.fail(TeamsDecodeException(
                                    IdentityPlatformMethod,
                                    TeamsDecodeException.Part.Token,
                                    TeamsDecodeException.Failure.Range,
                                    Chunk("expires_in"),
                                    Absent
                                ))
                            else Fetched(new AccessToken(answer.accessToken), requested + answer.expiresIn.seconds)
                        }
                    }
                else
                    rejected(IdentityPlatformMethod, response).map {
                        case Present(leaf) => Abort.fail(leaf)
                        case Absent        =>
                            Json.decode[Wire.IdentityError](response.fields.body) match
                                case Result.Success(error) if status.isClientError =>
                                    Abort.fail(TeamsTokenRejectedException(
                                        code(error.error),
                                        error.errorCodes,
                                        error.traceId.map(TeamsException.bounded(_, 64)),
                                        error.correlationId.map(TeamsException.bounded(_, 64))
                                    ))
                                case _ => Abort.fail(TeamsUnexpectedStatusException(IdentityPlatformMethod, status))
                    }
                end if
            }
        }
    end identityPlatform

    private def managedIdentity(endpoint: HttpUrl, header: Teams.IdentityHeader)(using Frame): Fetched < (Async & Abort[Failure]) =
        val resource = if config.scope.endsWith(DefaultScopeSuffix) then config.scope.dropRight(DefaultScopeSuffix.length) else config.scope
        Connector.transport(config, http, ManagedIdentityMethod, endpoint, config.requestTimeout)(
            HttpClient.getTextResponse(
                endpoint,
                headers = HttpHeaders.empty.add("X-IDENTITY-HEADER", header.value),
                query = HttpQueryParams.init("resource" -> resource, "api-version" -> "2019-08-01", "client_id" -> config.appId.value),
                failOnError = false
            )
        ).map { response =>
            if response.status.isSuccess then
                decode[Wire.IdentityTokenResponse](ManagedIdentityMethod, response.fields.body).map { answer =>
                    checkType(answer.tokenType).andThen {
                        val seconds = answer.expiresOn.trim
                        if seconds.isEmpty || seconds.length > 12 || !seconds.forall(c => c >= '0' && c <= '9') then
                            Abort.fail(TeamsDecodeException(
                                ManagedIdentityMethod,
                                TeamsDecodeException.Part.Token,
                                TeamsDecodeException.Failure.Range,
                                Chunk("expires_on"),
                                Absent
                            ))
                        else Fetched(new AccessToken(answer.accessToken), Instant.of(seconds.toLong.seconds, Duration.Zero))
                        end if
                    }
                }
            else
                rejected(ManagedIdentityMethod, response).map {
                    case Present(leaf) => Abort.fail(leaf)
                    case Absent        => Abort.fail(TeamsUnexpectedStatusException(ManagedIdentityMethod, response.status))
                }
        }
    end managedIdentity

    /** A rate limit, as its own leaf; any other non-2xx is the caller's to read. */
    private def rejected(method: String, response: HttpResponse["body" ~ String])(using
        Frame
    ): Maybe[TeamsRateLimitException] < Sync =
        if response.status == HttpStatus.TooManyRequests then
            Present(TeamsRateLimitException(method, response.headers.get("Retry-After").flatMap(Connector.parseRetryAfter), Absent, Absent))
        else Absent

    private def decode[A: Schema](method: String, body: String)(using Frame): A < Abort[TeamsDecodeException] =
        Json.decode[A](body) match
            case Result.Success(a)  => a
            case Result.Failure(ex) => Abort.fail(TeamsDecodeException.of(method, TeamsDecodeException.Part.Token, ex))
            case Result.Panic(ex)   => Abort.panic(ex)

    private def checkType(tokenType: String)(using Frame): Unit < Abort[TeamsUnsupportedTokenTypeException] =
        if tokenType.equalsIgnoreCase("Bearer") then Kyo.unit
        else Abort.fail(TeamsUnsupportedTokenTypeException(TeamsException.bounded(tokenType, 32)))

    private def code(error: String): TeamsTokenRejectedException.Code =
        import TeamsTokenRejectedException.Code
        error match
            case "invalid_request"        => Code.InvalidRequest
            case "invalid_client"         => Code.InvalidClient
            case "invalid_grant"          => Code.InvalidGrant
            case "unauthorized_client"    => Code.UnauthorizedClient
            case "unsupported_grant_type" => Code.UnsupportedGrantType
            case "invalid_scope"          => Code.InvalidScope
            case other                    => Code.Other(TeamsException.bounded(other, 64))
        end match
    end code

end TokenCache

private[kyo] object TokenCache:

    /** What a token fetch can fail with. */
    type Failure = TeamsTransportException | TeamsUnexpectedStatusException | TeamsDecodeException | TeamsRateLimitException |
        TeamsTokenRejectedException | TeamsUnsupportedTokenTypeException | TeamsCredentialException

    inline val IdentityPlatformMethod = "POST oauth2/v2.0/token"
    inline val ManagedIdentityMethod  = "GET MSI/token"

    private inline val JwtBearer          = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer"
    private inline val DefaultScopeSuffix = "/.default"

    def init(config: TeamsConfig, http: HttpClient)(using Frame): TokenCache < Sync =
        AtomicRef.init[State](Empty).map(new TokenCache(config, http, _))

    final private[teams] case class Fetched(token: AccessToken, expiresAt: Instant)

    sealed private[teams] trait State
    private[teams] case object Empty                                              extends State
    final private[teams] case class Ready(token: AccessToken, expiresAt: Instant) extends State

    /** A fetch in flight; compared by reference, so a settled fetch cannot replace a newer one. */
    final private[teams] class Fetching(val fiber: Fiber[Fetched, Abort[Failure]]) extends State

    /** `application/x-www-form-urlencoded` encoding: every byte outside the unreserved characters percent-encoded, space included. */
    private object Form:
        def encode(text: String): String = ServiceUrls.encode(text)
end TokenCache
