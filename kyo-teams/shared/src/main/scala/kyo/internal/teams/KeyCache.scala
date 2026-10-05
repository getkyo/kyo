package kyo.internal.teams

import kyo.*

/** The Bot Framework's signing keys of one client, by `kid`, read from the key set the OpenID metadata names.
  *
  * The set is fetched when there is none, when it is `keysMaxAge` old (Microsoft: refresh "at least once every 24 hours"), and when a
  * token names a `kid` the set lacks and the set is at least `keysMinRefresh` old; within `keysMinRefresh` an unknown `kid` fails
  * without a request, so a burst of forged tokens cannot make the client fetch. Concurrent lookups share one fetch, which runs on a
  * fiber of its own. A failed fetch leaves the previous set in place, aged as it was, and fails every lookup that waited on it.
  */
final private[kyo] class KeyCache private (config: TeamsConfig, http: HttpClient, state: AtomicRef[KeyCache.State], hooks: KeyCache.Hooks):
    import KeyCache.*

    /** The key `kid` names, fetching the set when the rules above say so. */
    def get(kid: String)(using Frame): Wire.Key < (Async & Abort[Failure | TeamsUnknownKeyException]) =
        Clock.now.map { now =>
            state.get.map {
                case ready: Ready if now < ready.fetchedAt + config.keysMaxAge =>
                    ready.keys.get(kid) match
                        case Present(key)                                             => key
                        case Absent if now >= ready.fetchedAt + config.keysMinRefresh => refresh(ready).map(lookup(_, kid))
                        case Absent                                                   => Abort.fail(unknown(kid))
                case fetching: Fetching => fetching.fiber.getResult.map(settle(fetching, _)).map(lookup(_, kid))
                case current            => refresh(current).map(lookup(_, kid))
            }
        }

    private def lookup(ready: Ready, kid: String)(using Frame): Wire.Key < Abort[TeamsUnknownKeyException] =
        ready.keys.get(kid) match
            case Present(key) => key
            case Absent       => Abort.fail(unknown(kid))

    private def unknown(kid: String)(using Frame): TeamsUnknownKeyException = TeamsUnknownKeyException(TeamsException.bounded(kid, 128))

    // The fetch starts only once this caller's `Fetching` is the state. Starting it before the claim lets every caller that read the same
    // state send a request, and interrupting a loser's fetch does not unsend one already written. The claim and the start are one
    // uninterruptible step: a caller interrupted between them would leave a claim no fetch completes, and every later lookup waiting on it.
    private def refresh(current: State)(using Frame): Ready < (Async & Abort[Failure]) =
        claim.map { claim =>
            val fetching = new Fetching(claim, current)
            hooks.beforeClaim.andThen(Async.uninterruptible(state.compareAndSet(current, fetching).map { won =>
                if won then hooks.fetchStarting.andThen(Fiber.initUnscoped(fetch)).map(claim.becomeDiscard).andThen(true) else false
            })).map { won =>
                if won then claim.getResult.map(settle(fetching, _))
                else
                    state.get.map {
                        case other: Fetching => other.fiber.getResult.map(settle(other, _))
                        case other: Ready    => other
                        case other           => refresh(other)
                    }
            }
        }

    // Uninterruptible, so a lookup interrupted while it waits on the fetch does not fail the others waiting on it.
    private def claim(using Frame): Fiber.Promise[Ready, Abort[Failure]] < Sync =
        // Unsafe: kyo offers an uninterruptible promise only in the unsafe tier; it is created here and used through `.safe`.
        Sync.Unsafe.defer(Fiber.Promise.Unsafe.initUninterruptible[Ready, Abort[Failure]]().safe)

    private def settle(fetching: Fetching, result: Result[Failure, Ready])(using Frame): Ready < (Sync & Abort[Failure]) =
        result match
            case Result.Success(ready)   => state.compareAndSet(fetching, ready).andThen(ready)
            case Result.Failure(failure) => state.compareAndSet(fetching, fetching.previous).andThen(Abort.fail(failure))
            case Result.Panic(ex)        => state.compareAndSet(fetching, fetching.previous).andThen(Abort.panic(ex))

    private def fetch(using Frame): Ready < (Async & Abort[Failure]) =
        val metadataUrl = config.openIdMetadataUrl
        Connector.transport(config, http, MetadataMethod, metadataUrl, config.requestTimeout)(
            HttpClient.getTextResponse(metadataUrl, failOnError = false)
        ).map { response =>
            if !response.status.isSuccess then Abort.fail(TeamsUnexpectedStatusException(MetadataMethod, response.status))
            else
                decode[Wire.OpenIdMetadata](MetadataMethod, TeamsDecodeException.Part.Metadata, response.fields.body).map { metadata =>
                    if metadata.issuer != config.issuer then
                        Abort.fail(TeamsWrongIssuerException(TeamsException.bounded(metadata.issuer, 256)))
                    else if !metadata.algorithms.contains("RS256") then
                        Abort.fail(TeamsMetadataAlgorithmException(metadata.algorithms.take(8).map(TeamsException.bounded(_, 16))))
                    else
                        keySetUrl(metadata.jwksUri) match
                            case Absent       => Abort.fail(TeamsRefusedUrlException(KeysMethod))
                            case Present(url) => keySet(url)
                }
        }
    end fetch

    /** The key set's URL when it is one the module sends to: https on a TCP host, or the configured metadata URL's own origin. */
    private def keySetUrl(text: String)(using Frame): Maybe[HttpUrl] =
        ServiceUrls.parse(text) match
            case Result.Success(url)
                if url.scheme.exists(_.equalsIgnoreCase("https")) || ServiceUrls.allowed(Chunk(config.openIdMetadataUrl), url) =>
                Present(url)
            case _ => Absent

    private def keySet(url: HttpUrl)(using Frame): Ready < (Async & Abort[Failure]) =
        val httpConfig =
            Connector.requestConfig(config, config.requestTimeout).copy(maxResponseLength =
                StreamCoreExtensions.readBufferCapacity(config.keysMaxResponseLength)
            )
        Connector.transportWith(httpConfig, http, KeysMethod, url)(HttpClient.getTextResponse(url, failOnError = false)).map { response =>
            if !response.status.isSuccess then Abort.fail(TeamsUnexpectedStatusException(KeysMethod, response.status))
            else
                decode[Wire.KeySet](KeysMethod, TeamsDecodeException.Part.Keys, response.fields.body).map { set =>
                    Clock.now.map { now =>
                        val usable = set.keys.filter(k => k.kty == "RSA" && k.use.forall(_ == "sig") && k.n.nonEmpty && k.e.nonEmpty)
                        val keys   = usable.foldLeft(Dict.empty[String, Wire.Key]) { (byId, key) =>
                            key.kid match
                                case Present(kid) if !byId.contains(kid) => byId.update(kid, key)
                                case _                                   => byId
                        }
                        Ready(keys, now)
                    }
                }
        }
    end keySet

    private def decode[A: Schema](method: String, part: TeamsDecodeException.Part, body: String)(using
        Frame
    ): A < Abort[TeamsDecodeException] =
        Json.decode[A](body) match
            case Result.Success(a)  => a
            case Result.Failure(ex) => Abort.fail(TeamsDecodeException.of(method, part, ex))
            case Result.Panic(ex)   => Abort.panic(ex)

end KeyCache

private[kyo] object KeyCache:

    /** What fetching the metadata and the key set can fail with. */
    type Failure = TeamsTransportException | TeamsRefusedUrlException | TeamsUnexpectedStatusException | TeamsDecodeException |
        TeamsWrongIssuerException | TeamsMetadataAlgorithmException

    inline val MetadataMethod = "GET openid-metadata"
    inline val KeysMethod     = "GET jwks"

    def init(config: TeamsConfig, http: HttpClient)(using Frame): KeyCache < Sync =
        init(config, http, Hooks.none)

    private[kyo] def init(config: TeamsConfig, http: HttpClient, hooks: Hooks)(using Frame): KeyCache < Sync =
        AtomicRef.init[State](Empty).map(new KeyCache(config, http, _, hooks))

    /** Points inside `refresh` that only a test fills in, because no input a test controls can hold a lookup between reading the state and
      * claiming the fetch. `beforeClaim` runs after a lookup has read the state it replaces and before its claim; `fetchStarting` runs in
      * the lookup just before it starts the fetch. Holding every lookup in `beforeClaim` and counting `fetchStarting` makes a fetch that
      * starts before its claim deterministic to catch.
      */
    final private[kyo] case class Hooks(beforeClaim: Unit < Async, fetchStarting: Unit < Sync)

    private[kyo] object Hooks:
        val none: Hooks = Hooks(Kyo.unit, Kyo.unit)

    sealed private[teams] trait State
    private[teams] case object Empty extends State

    final private[teams] case class Ready(keys: Dict[String, Wire.Key], fetchedAt: Instant) extends State

    /** A fetch in flight, compared by reference, and the state it replaces when it fails. */
    final private[teams] class Fetching(val fiber: Fiber[Ready, Abort[Failure]], val previous: State) extends State

end KeyCache
