# Contributing to kyo-teams

Module-specific guide for kyo-teams. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: naming, the kyo type vocabulary (`Maybe`, `Result`, `Chunk`), failure tracking, scaladoc, the `KyoException` convention, deterministic tests and the unsafe tiers all apply here unchanged and are not repeated. This document records what is specific to kyo-teams: the two guarantees the module exists to keep, the layering that keeps them, its failure model, the wire-model shapes, the recipes for extending it, and how its tests are built.

Citations name the file and the symbol, never a line. When a symbol is renamed or moved, fix the citation with it.

## What kyo-teams is

A Microsoft Teams bot client over kyo-http. The public surface is four files in package `kyo`:

- `Teams.scala`: the client `Teams`, the outbound verbs on its companion (`send`, `reply`, `edit`, `delete`, `typing`, `createConversation`, `members`, `member`, `custom`), `Teams.run`, `Teams.Webhook` (`handler`, `verify`, `decode`), and the model (ids, credentials, `ServiceUrl`, `Path`, `Activity`, `Message`, `Card`, `Conversation`, `Member`).
- `TeamsConfig.scala`: the validated settings record and its `Credential` cases.
- `TeamsWebhookConfig.scala`: where the webhook is mounted.
- `TeamsException.scala`: the sealed failure hierarchy.

Below it, six `private[kyo]` objects in `kyo.internal.teams` do the work: `Connector` (the one Bot Connector request path), `TokenCache`, `KeyCache`, `Jwt`, `ServiceUrls` and `Wire`. Everything is in `shared/`; the build differs per platform only in settings (`build.sbt`, the `kyo-teams` project).

A caller never calls a method on a `Teams`. The verbs require it as `Env[Teams]`, and only `Teams.run` provides it: `run(config)` for a region whose end closes its HTTP client and drops its caches, `run(client)` for a client `init` (closed with its `Scope`) or `initUnscoped` (closed by `close`) built, the shapes kyo-slack and kyo-discord have (`Teams.scala`, the `Teams` scaladoc and `Teams.run`). A method that satisfies a pending effect is named `run`: `Webhook.handler` requires `Env[Teams]` too and serves every delivery with the caller's client, so a bot has one client and one token cache. Do not add another method that builds a client and provides it.

## The two guarantees

Everything else in the module serves two properties. A change that weakens either is a security bug, whatever else it fixes.

### The outbound token goes only to an allowlisted origin

Anyone holding the bot's access token may act as the bot. The token is therefore sent only to a service URL whose origin `TeamsConfig.serviceHosts` lists, and that is checked before every request (`Teams.scala`, the `Teams` scaladoc).

The check is the first step of `Connector.call`, before `teams.tokens.get` is reached. A refused URL fetches no token and sends nothing (`Connector.scala`, `Connector.call`). An origin matches on scheme and host, ASCII case-insensitively, and on the exact port (`ServiceUrls.scala`, `ServiceUrls`).

A well-formed `Teams.ServiceUrl`, whether from `init`, from its `Schema`, or inside a stored `ConversationReference`, is not yet a URL the module sends to. Every outbound request must go through `Connector.call`. A new path that calls kyo-http directly skips the allowlist and leaks the token. `TeamsTest` "a service URL whose origin serviceHosts does not list is refused before a token is requested or anything is sent" pins that a refused send reaches the peer with no request at all.

The module also owns the whole kyo-http configuration of a request that carries a credential. `Connector.requestConfig` builds a complete `HttpClientConfig` from the `TeamsConfig`: no redirects, no kyo-http retry, no auto filters, the config's `tls` and `transport` (`Connector.scala`, `Connector.requestConfig`). `transportWith` installs it with `HttpClient.let` and `HttpClient.withConfig` (`Connector.scala`, `Connector.transportWith`). So no caller filter, TLS setting or redirect policy reaches a request with a token in it. A new setting that changes how a credential-carrying request is sent belongs in `requestConfig`; a knob read anywhere else does not reach the request.

### An inbound delivery is accepted only after ordered verification

`Jwt.verify` runs Microsoft's "Verify the JWT token" requirements as one for-comprehension, so each check runs only when every earlier one passed (`Jwt.scala`, the `Jwt` scaladoc and `Jwt.verify`):

1. `bearer`: the `Authorization` header, the `Bearer ` scheme, and `maxTokenLength`.
2. `split` and `decoded`: three canonical base64url segments, the header and the required claims.
3. `signedWith`: `alg` is `RS256` and a `kid` is present.
4. `lifetime`: `exp` and `nbf` are NumericDates in range.
5. `issuedFor`: the issuer, then the audience.
6. `current`: the time is inside the skew window.
7. `teams.keys.get(kid)`: the key, which may fetch.
8. `signed`: the RS256 signature under that key.
9. `bound`: the body's `channelId` is endorsed by the key and is `msteams`, and its `serviceUrl` equals the token's `serviceUrl` claim as an exact string (`Jwt.scala`, `Jwt.bound`).

The order is load-bearing in two ways. A forged token reaches a key fetch only with a plausible issuer, audience and lifetime, so moving `teams.keys.get` earlier lets an unauthenticated caller drive key-set fetches. The body is parsed only under a verified signature, so reading it before `signed` parses attacker-controlled bytes pre-authentication. The key cache's throttle on unknown key ids assumes the issuer, audience and lifetime filter already ran (`KeyCache.scala`, the `KeyCache` scaladoc).

Only `RS256` is ever verified, whatever the token or the metadata lists, so `none` and every HMAC algorithm are refused before a key is touched. The OpenID metadata must also list `RS256`, or the fetch fails with `TeamsMetadataAlgorithmException` (`KeyCache.scala`, `KeyCache`'s metadata fetch). `JwtTest` "only RS256 is accepted, whatever the token asks: none, HMAC and RS512 are refused before a key is fetched" pins both the refusal and the absence of a fetch.

Verification cannot be turned off. Microsoft's guidance is "Implementers shouldn't expose a way to disable validation", and `Teams.Webhook` has no option to skip `verify` (`Teams.scala`, the `Teams.Webhook` scaladoc). `handler` runs `Jwt.verify`, then `decode`, then `f` (`Teams.scala`, `Teams.Webhook.handler`). Do not add a flag, a test-only bypass in main code, or a `decode` path that a caller can reach without `verify` having run in the same handler.

## Architecture

### One request path out

Each outbound verb is an `Env.use[Teams]` around `Connector.call[<Op>Failure]` with its `Routes` constant, method, service URL and path segments, followed by `decoded[...]` of the 2xx body (`Teams.scala`, `Teams.send`). `custom` reaches the same `call` with a caller-built `Path`, so it inherits the allowlist, the token and the error mapping.

`Connector.call` does, in order (`Connector.scala`, `Connector.call`):

1. The allowlist check.
2. Joining the raw segments under the service URL. `ServiceUrls.join` percent-encodes each segment, so segments go to `call` unencoded.
3. Per attempt: the token from `TokenCache`, a bearer header, and the request under `transport` with `failOnError = false`, so the module maps the status, `Retry-After` and body itself.
4. A 2xx answers the body. Otherwise `retry` may send again, and when it does not, `failure` turns the status and body into the operation's leaf.

Retry is opt-in. It applies only to Bot Connector calls answered 412, 429, 502, 503 or 504. The wait is the longer of the schedule's delay and `Retry-After`, capped at `retryMaxDelay`. A `Retry-After` above that cap fails at once instead of waiting (`Connector.scala`, `Connector.retry`). The token request and the key fetches never retry: they call `transport` directly, not `call`.

### Two single-flight caches

`TokenCache` holds one outbound token per client, reused until `tokenRefreshMargin` before its expiry (`TokenCache.scala`, the `TokenCache` scaladoc). `KeyCache` holds the signing keys by `kid`, read from the key set the OpenID metadata names. It refetches when it holds no set, when the set is `keysMaxAge` old, or when a `kid` is unknown and the set is at least `keysMinRefresh` old (`KeyCache.scala`, the `KeyCache` scaladoc). Both route their HTTP through `Connector.transport` or `transportWith`, so every module request shares one transport-failure mapping and one `requestConfig`.

Both caches share one concurrency design, and it is load-bearing:

- **The fetch runs on a fiber of its own.** A caller interrupted while it waits does not leave the others waiting on a fetch that stopped (`TokenCache.scala`, the `TokenCache` scaladoc).
- **The in-flight state is a plain class, compared by reference.** `Fetching` is installed with `compareAndSet(current, fetching)` and settled with `compareAndSet(fetching, ...)`, so a settled fetch can never replace a newer state. A losing racer interrupts its own fiber and retries (`TokenCache.scala`, `TokenCache.Fetching` and `TokenCache.get`). Making `Fetching` a case class gives it structural equality and breaks this: a stale settle could overwrite a newer fetch.
- **A failure restores, it does not cache.** A failed token fetch settles to `Empty`, so the next call fetches again (`TokenCache.scala`, `TokenCache.get`). A failed key fetch restores the previous set, aged as it was (`KeyCache.scala`, `KeyCache.get` and its fetch).

The key set URL the metadata names is followed only when it is https, or on the configured metadata URL's own origin (`KeyCache.scala`, the `jwks_uri` check). Only RSA `sig` keys with `n` and `e` are kept, and a repeated `kid` keeps the first (`KeyCache.scala`, the key set decode).

`TokenCache.fetch` runs a federated credential's own computation outside the module's transport and decode regions, so the `TeamsCredentialException` it fails with reaches the caller unchanged (`TokenCache.scala`, `TokenCache.fetch`). That leaf is in `TokenCache.Failure`, hence in `Connector.Common`, so every outbound verb's trait carries it. The caller builds it from its own failure; its message keeps only a `Throwable` cause's class name and at most 200 bounded characters of a `String` cause, and a `Throwable` cause is its `getCause` (`TeamsException.scala`, `TeamsCredentialException`).

### The webhook

`Webhook.handler` takes the caller's `Teams` from `Env[Teams]`, mounts a raw-bytes `POST` route that verifies with it and runs `f` with it, and answers by the Activity's answer index (`Teams.scala`, `Teams.Webhook.handler` and `Teams.Webhook.answer`):

- `AdaptiveCardAction`: `f` at `Card.ActionResponse`, written as JSON in a 200.
- `OtherInvoke`: `f` at `InvokeResponse`, whose own status and body become the reply.
- every `Activity.Plain`: `f` at `Unit`, then an empty 200.

A delivery that does not verify is answered by `refusal`, one exhaustive match (`Teams.scala`, `Teams.Webhook.refusal`): 403 for a missing endorsement (Microsoft's answer for it), 503 for `TeamsMetadataAlgorithmException` and for any failure fetching the keys (the delivery may be genuine, so the Bot Connector may resend), 401 for every other `TeamsAuthenticationException`, and 400 for a body that does not decode. Which status a new verify leaf gets is a security decision: 401 tells the Bot Connector the delivery is not genuine, 503 tells it to try again.

## The failure model

### One sealed trait per operation

Every failure is a leaf of `sealed abstract class TeamsException` over `KyoException` (`TeamsException.scala`, `TeamsException`). Each public operation has its own sealed trait, `Teams<Op>Failure` (`TeamsException.scala`, the `Teams<Op>Failure` traits), and a leaf extends the trait of every operation that can produce it. A verb's row is therefore exactly the set of leaves a caller can meet, and no row names `TeamsException` itself (`TeamsException.scala`, the file's header comment). Which operations a leaf belongs to is declared on the leaf, never on the verb.

`Connector.Common` is the union of leaves any Bot Connector call can fail with: the token cache's failures, the refused URL, and the three API leaves Microsoft documents for every route, plus the catch-all (`Connector.scala`, `Connector.Common`). `call` is declared `call[F >: Common]`, so an operation trait that is not mixed into every `Common` leaf does not compile at the verb.

### Named API leaves and the catch-all

A Bot Connector `ErrorResponse` is mapped to a named leaf by `(status, code)` in `Connector.within`. The leaf is kept only when it carries the operation's trait `F`, checked through `TypeTest[TeamsException, F]`. Otherwise the answer becomes `TeamsOtherApiException`, keeping the status, code and description (`Connector.scala`, `Connector.within`). A named leaf stands for one code and fixes its `status` and `code`; only the catch-all carries what it received (`TeamsException.scala`, `TeamsApiException`).

This is the one closed list the compiler does not check. `within` ends in a wildcard, and a leaf mixed into too few traits only downgrades to `TeamsOtherApiException`. `TeamsTest` "each ErrorResponse a send can receive is its own leaf" and "a leaf the operation cannot receive is the catch-all" are the only proof that an arm exists and lands where intended.

### No leaf holds a credential

No leaf, message or rendering carries a credential (`TeamsException.scala`, the file's header comment). Four mechanisms hold that line, and a new failure path must use all of them:

- **Redaction.** `Connector.failure` builds the secrets of the request: the access token, plus the credential's held text (the client secret, or a managed identity's header). `redact` replaces each one, raw and percent-encoded, in Microsoft's `description` (`Connector.scala`, `Connector.failure` and `Connector.redact`). A federated assertion is computed per fetch and held nowhere, so its arm is empty with a comment saying so.
- **Bounding.** Text a peer chooses (an issuer, a key id, an algorithm, a code, an operation id) passes through `TeamsException.bounded`, which truncates it and replaces non-printable characters (`TeamsException.scala`, `TeamsException.bounded`).
- **No quoting on decode.** A decode leaf keeps kyo-schema's failure kind, path and position, never the `DecodeException`, because that exception quotes the input, and the input can be an access token or a user's text (`TeamsException.scala`, `TeamsDecodeException`). Every decode site maps through `TeamsDecodeException.of` or `TeamsWebhookDecodeException.of`.
- **Redacted `toString`.** Every credential type and the internal `AccessToken` render as `<redacted>`, compare by value, and expose their text only through `value` (`TokenCache.scala`, `AccessToken`). `RawJson` renders as `<redacted>` too, since it can hold what a user wrote, and its `value` takes the caller's `Frame` (`Teams.scala`, `Teams.RawJson`).

`TeamsTransportException` keeps kyo-net's cause of a failed connection only as `getCause`, outside `KyoException`'s message and the leaf's equality (`TeamsException.scala`, `TeamsTransportException`). `TeamsCredentialException` keeps a `Throwable` cause the same way, outside its message, which names only the cause's class.

### Exhaustive matches under -Werror

The module compiles with `-Werror` through `kyo-settings` (`build.sbt`, `kyo-settings` and the `kyo-teams` project), so a non-exhaustive match on a sealed type fails the build. The module relies on that for every closed list except `within`. These matches have no wildcard, and a new case fails the build until it is placed:

- `Webhook.answer` and `Webhook.refusal` (`Teams.scala`);
- `TokenCache.fetch` over the credential (`TokenCache.scala`);
- the secrets match in `Connector.failure` (`Connector.scala`);
- the credential match in `TeamsConfig.problemOf` (`TeamsConfig.scala`);
- `Connector.describe` over kyo-http's failures (`Connector.scala`);
- every `Problem.show`.

Keep it that way: never add a `case _` to one of these to quiet the compiler. The arm the compiler asks for is the decision.

## Conventions

### Validated values

A value Teams would refuse is built only by `init(...)(using Frame): Result[TeamsInvalid<X>Exception, X]`. `init` is pure and never aborts or throws. The validation is a pure `problemOf(...): Maybe[Problem]`, matched as `Present(problem) => Result.fail(...)` and `Absent => Result.succeed(...)` (`Teams.scala`, `Teams.Ids.init`). A validation leaf carries `problem: <Leaf>.Problem`, an enum whose cases hold positions and counts, never the refused text.

A validating `Schema` delegates to its type's `init`, so decoding refuses exactly what construction refuses, with the same leaf. The constructor `transformVia` and `derivedVia` take receives the decode call's `Frame`, so the leaf names the decode site; a given passes no `Frame` of its own, and none can be derived in package `kyo`. The ids do it through `Ids.schema` (`Teams.scala`, `Teams.Ids.schema`), the credentials, `ServiceUrl` and `Card.Version` through `transformVia` over `init` (`Teams.scala`, the `given Schema` of `IdentityHeader`, `ServiceUrl` and `Card.Version`), and `Card.Refresh` through `Schema.derivedVia` over its private-constructor `init` (`Teams.scala`, the `given Schema` of `Card.Refresh`). `InvokeResponse` is the one schema that checks in place: kyo-http has no validating constructor for a status, so its `transformVia` refuses a code outside 100 to 599 itself (`Teams.scala`, the `given Schema` of `InvokeResponse`). Do not write a second validation inside a `Schema` that has an `init` to delegate to.

Each id is an opaque type in an object of its own: `type X = X.Value`, `object X` with `opaque type Value = String`, `init`, `value`, `given Schema` and `given CanEqual` (`Teams.scala`, `Teams.AppId`). Declared directly in `object Teams`, the opaque type would be transparent there, and `Schema.derived` of the records beside it would lose its field list (`Teams.scala`, the comment above `Teams.AppId`).

### Verbs

A verb is `def verb(...)(using Frame): A < (Async & Abort[Teams<Verb>Failure] & Env[Teams])`. It names itself in failures with an `inline val` in `Teams.Routes`, written as the Bot Connector's reference writes the route (`Teams.scala`, `Teams.Routes`). It answers what the call answers: the id Teams assigns (`ActivityId`), a modelled value, or `Unit` for an empty answer. A convenience form is an overload delegating to the canonical one, as `reply(activity, message)` delegates to `reply(ref, to, message)` (`Teams.scala`, `Teams.reply`).

### The wire model is open-world

Microsoft adds Activity types, invoke names, events, entities, card elements and actions over time. Every inbound sum therefore ends in a catch-all that decodes an unmodelled value and encodes it back unchanged:

- A string-valued enum is a `@tagOnly sealed trait` of `@rename("<wire>") case object`s plus `@catchAll final case class Other(name: String)`, with `given Schema[X] = Schema.derived[X]`. `ChannelData.Event` is an example, with `@alias` for a second spelling Teams sends (`Teams.scala`, `Teams.ChannelData.Event`).
- An object-valued sum is a `@discriminator("<key>") sealed trait` with a `@catchAll final case class Other(<key>: String, payload: RawJson)`, as `Entity`, `Attachment`, `Card.Element` and `Card.Action` are (`Teams.scala`, `Teams.Entity`, `Teams.Attachment`, `Teams.Card.Element` and `Teams.Card.Action`).
- `Activity.Unknown` is the catch-all of the `type` discriminator, and `OtherInvoke` of the invoke `name` (`Teams.scala`, `Teams.Activity.Unknown` and `Teams.Activity.OtherInvoke`).

A new inbound sum without a catch-all turns a value Microsoft adds into a decode failure, which the webhook answers 400 after a successful verification: a genuine Activity is dropped.

Records follow fixed shapes. An optional field is `Maybe[A] = Absent` and a collection field is `Chunk[A] = Chunk.empty`, with the companion's `given Schema[X] = Schema[X].omitNone` plus `.omitEmptyCollections` when it has `Chunk` fields. A modelled Activity case flattens its `common` with `Schema[X].flatten(_.common)` (`Teams.scala`, the `given Schema` of `Teams.Activity.Message`). A wire constant the model does not hold as a field (a card's `type`, an action response's `statusCode`) is added with `.add(...)`, not as a case-class field (`Teams.scala`, the `given Schema` of `Teams.Card`). A wire document with no public type is a `final case class ... derives Schema` in the internal `Wire` object.

`Action.Execute` is the one action used as a field outside the `Action` sum, in `Card.Refresh` and in `AdaptiveCardAction`'s request. Its given leaves `type` out, because inside the sum the discriminator writes the tag and kyo-schema refuses a variant field under the tag key. The two fields read and write it through `Execute.Standalone`, a `Transformer.Of` that adds `type`, as `@transform(Action.Execute.Standalone)`. A new action used standalone needs the same transformer on its fields, or its JSON lacks the tag.

## Extending the module

### Adding a Bot Connector verb

1. A sealed `Teams<Verb>Failure` trait beside the others, with a one-line scaladoc naming the verb (`TeamsException.scala`, `TeamsSendFailure`).
2. Mix it into every `Common` leaf. Until you do, `Connector.call[Teams<Verb>Failure]` does not compile.
3. Mix it into each named `TeamsApiException` leaf Microsoft documents for the route, and no other. Too few and those codes arrive as `TeamsOtherApiException`; too many and the row claims leaves the route cannot produce.
4. The route in `Teams.Routes`, passed to both `Connector.call` and `decoded`.
5. The verb on the `Teams` companion in the shape `send` has (`Teams.scala`, `Teams.send`). A body or answer with no public type is a record in `Wire`.
6. A label in `TeamsLocal.label` (`TeamsLocal.scala`, `TeamsLocal.label`). Labels match top to bottom, and `typing` and `send` share a method and path, told apart by the body; an overlapping route goes above the broader case.
7. A `TeamsTest` leaf that queues a reply under the label and asserts the result and what the peer recorded: method, encoded path, authorization and body.

### Adding a named error leaf

1. `final case class Teams<Name>Exception(method: String, description: String, operationId: Maybe[String])(using Frame)` extending `TeamsApiException(TeamsApiException.describe(method, <status>, "<Code>", description))`, with `status` and `code` fixed as `def`s and the trait of each operation Microsoft documents the code for, as `TeamsActivityNotFoundException` is (`TeamsException.scala`, `TeamsActivityNotFoundException`).
2. Its `(status, code)` arm in `Connector.within`, above the wildcard (`Connector.scala`, `Connector.within`).
3. A row in the `ErrorResponse` table of `TeamsTest`, the leaf "each ErrorResponse a send can receive is its own leaf". Without the row nothing proves the arm exists.

### Adding an Activity type or an invoke

An Activity answered with 200 alone is a `@rename("<type>") final case class X(common: Common, ...)` that `extends Activity[Unit] with Addressed derives CanEqual`, with a companion `Schema` flattening `common` (`Teams.scala`, `Teams.Activity.InstallationUpdate`). Add it to the `Activity.Plain` union (`Teams.scala`, `Teams.Activity.Plain`); `Webhook.answer` matches `Plain` as its last arm, so a case left out fails the build.

An invoke is a `@rename("<invoke name>")` case that `extends Invoke with Activity[<Answer>] with Addressed`, picked up by the `name` discriminator of `Invoke` (`Teams.scala`, `Teams.Activity.Invoke`). A new answer type joins the `Activity.Answer` union at which the root `Schema` is given (`Teams.scala`, `Teams.Activity.Answer` and the `given Schema` of `Teams.Activity`), and the invoke gets its own arm in `Webhook.answer`.

Either way, `TeamsActivityTest` decodes Microsoft's documented example into the case and round-trips it, and an invoke adds its arm to the handler leaf's matcher, "a handler answers each case with the type its index names".

### Adding a card element, action or constrained card value

An element is a `@rename("<Adaptive Card type>") final case class` in `Card.Element`; an action the same in `Card.Action`, above the `@catchAll` `Other`. Add an instance to the round-trip card of `TeamsCardTest` "every modelled element and action round-trips". A value with bounds (as `Card.Refresh` has 60 users at most) gets a private constructor, an `init` answering `Result[TeamsInvalidCardException, X]`, a `TeamsInvalidCardException.Problem` case with its `show` arm, and a `Schema` through `init` (`Teams.scala`, `Teams.Card.Refresh`).

### Adding a credential

1. Its secret as a `final class X private (val value: String)` with equality by value, `toString` of `Teams.X(<redacted>)`, `init` through `Credentials.init`, and a `Schema` through `init`, as `IdentityHeader` is (`Teams.scala`, `Teams.IdentityHeader`). A length bound goes in only when Microsoft documents one, as for `ClientSecret`, with the source cited in the scaladoc; a bound neither Microsoft nor the request imposes is not added.
2. A `TeamsInvalidTokenException.Token` case with its `show` arm.
3. A case of the sealed `TeamsConfig.Credential`. A case that runs a caller computation types its failure as `Abort[TeamsCredentialException]`, as `Federated` does, so the caller maps its own failure into a leaf every verb's trait already carries (`TeamsConfig.scala`, `TeamsConfig.Credential.Federated`).
4. The build then asks for three arms:
   - in `TokenCache.fetch`, with a method constant beside `IdentityPlatformMethod` and `ManagedIdentityMethod`;
   - in the secrets match of `Connector.failure`, listing every text the request carries, or an empty chunk with a comment saying why there is nothing to redact;
   - in the credential match of `TeamsConfig.problemOf`, where a credential that holds a URL checks it with a `TeamsInvalidConfigException.Problem` case beside `IdentityEndpoint`.
5. A `TeamsLocal.label` arm for its token endpoint, `TokenCacheTest` leaves for the request it sends and each refusal, and a `TeamsTest` redaction leaf proving an echoed secret reads `<redacted>`, as "an answer echoing a managed identity's header keeps none of it" does.

### Adding a config setting

The field goes in the `TeamsConfig` case class, whose constructor is `private[kyo]`, and the same parameter with its default in `TeamsConfig.init`, in the same position (`TeamsConfig.scala`, `TeamsConfig` and `TeamsConfig.init`). When some values are unusable, add a `TeamsInvalidConfigException.Problem` case carrying the value (never secret text) and its check as an `.orElse` in `problemOf` at the setting's declaration position, since `init` reports the first problem in declaration order (`TeamsConfig.scala`, `TeamsConfig.problemOf`). A duration passed to kyo-http is held as a `Duration`, and every value kyo-http's `HttpClientConfig` would throw on (a timeout that is not positive) is a `Problem` there, so no request can throw on it. A byte quantity is a `ByteSize`, held as given and never refused; where it reaches kyo-http's `Int` limit, kyo-core's `StreamCoreExtensions.readBufferCapacity` narrows it (`Connector.scala`, `Connector.requestConfig`). State the setting and its default in the `TeamsConfig` scaladoc, and cover the default, the accepted boundary and each refused value in `TeamsConfigTest`.

### Adding an inbound verification check

Add a step to the `Jwt.verify` comprehension at the position Microsoft's order gives it, failing with a new `TeamsAuthenticationException` leaf. That category already carries `TeamsWebhookVerifyFailure` (`TeamsException.scala`, `TeamsAuthenticationException`), and `refusal` answers it 401 without a new arm. Give it its own arm only when Microsoft gives the failure another status. Pass any text the token chooses through `TeamsException.bounded`. In `JwtTest`, drive tokens through `refusals`, which verifies each on a fresh client and answers the number of metadata fetches the peer saw, so the leaf pins both the refusal and whether the check precedes the key fetch (`JwtTest.scala`, `JwtTest.refusals`).

## Testing

All suites are in `shared/src/test` and extend `kyo.test.Test[Any]`. Suites of internal machinery are in `kyo.internal.teams` and import the harness with `kyo.TeamsLocal.*`. The module depends on kyo-http's test scope (`build.sbt`, the `kyo-teams` project's dependencies), which is how suites reach `kyo.internal.TlsTestHelper`.

### The TeamsLocal peer

`TeamsLocal.withLocal` serves one local `HttpServer` that plays every remote party: the identity platform, the managed identity endpoint, the Bot Connector, and the OpenID metadata and key set (`TeamsLocal.scala`, the `TeamsLocal` scaladoc). Each request is recorded under a label that `label` derives from the method, path and body, and is answered with the next `Reply` queued under that label, the last one repeating. The harness pre-queues a 3600-second bearer token, the metadata, and a key set holding `k1` and `s1` (`TeamsLocal.scala`, `TeamsLocal.withLocal`).

An outbound test queues the answer with `local.reply(label, ...)`, runs the call through `local.api(...)` (which is `Teams.run(config)`), and asserts both the result and `local.seen(label)`. A different credential or config field is built from `local.config.copy(...)` or `local.configWith(credential)`, so the peer's URLs are kept. A test of a cache across calls builds one client with `Teams.client(config)` and runs each call with `Env.run(teams)`; a fresh `Teams.run` per call starts with empty caches.

Request bodies are compared as `TeamsJsonTree` trees sorted by key, never as strings. Error-mapping tests are table-driven: a `Chunk` of answer and expected leaf, run with `Kyo.foreach` and compared as a whole.

Suites that start a local `HttpServer` turn the socket leak check off: the server's closed connections are reaped on the selector's next pass, after the check runs (`TeamsTest.scala`, the suite's leak-check override).

### Time and concurrency

`withLocal` runs the test under `Clock.withTimeControl`, and a nested `Clock.withTimeControl { control => withLocal { ... } }` drives the same control. Token expiry, clock skew, cache ages and retry waits are driven with `control.advance` and `control.set`, testing a boundary one second before and at the edge, as `TokenCacheTest` "the token is reused until tokenRefreshMargin before it expires" does.

Concurrency is pinned with held requests, never sleeps. A label with nothing queued holds its request and releases `held`; `release` answers every held request with the reply then queued (`TeamsLocal.scala`, `release`). A single-flight test queues nothing under the label, starts its callers with `Fiber.initUnscoped`, waits on `local.held.await`, queues the real reply, calls `local.release`, joins, and counts requests per label, as `TokenCacheTest` "a burst of calls requests the token once" does. A request timeout is tested the same way: once `held.await` returns the request is in flight, then advance by `requestTimeout`.

A handler served by an `HttpServer` reads the server fiber's clock, not the test's controlled one. A webhook test that posts the Epoch-relative tokens of `local.claims()` gets 401 for every delivery. Those tests use tokens whose `exp` is the end of year 9999 (`TeamsWebhookTest.scala`, the suite's token fixtures).

### Signatures and secrets

A test of a check before the signature needs no valid signature. `unsigned` builds a token whose signature is 256 bytes no key produced, under `k1`, whose `modulus` is an odd 2048-bit value whose factors nobody knows (`TeamsLocal.scala`, `unsigned` and `modulus`). A token that passes every earlier check therefore ends at `TeamsSignatureMismatchException`, and a test uses that leaf as proof that those checks passed.

No test signs anything: kyo-crypto has no signing, and none is added for the tests. A test of the signature, the body binding, or a delivery the handler accepts uses `signed`, a token signed once offline with OpenSSL and vendored under `shared/src/test/vectors/bot-framework-tokens` with the key, the claims and a `MANIFEST` giving the exact commands; `project/TestVectorsGen.scala` checks each file's SHA-256 and embeds the `file` entries as `TeamsVectors`, so every platform reads them without a file system, while the test-only key is a `kept` entry, checked and never embedded (`TeamsLocal.scala`, `signed`). Its claims are fixed, so it names the public service URL `signedServiceUrl` rather than the peer's port, and a body under test carries that URL. JwtTest verifies it with the controlled clock set to its `iat`; a served handler, which reads the live clock, accepts it because it is valid from Epoch to the end of year 9999. A new signed case is a new file in the set, produced by the MANIFEST's commands and listed with its digest.

Secrets and private words used in a test are built from parts, since a failure's message quotes the source lines around its frame and would otherwise contain the literal (`TeamsLocal.scala`, the secret fixtures). A redaction test asserts both that the failure equals the expected leaf with `<redacted>` and that `TeamsLocal.rendered(e)` (message, rendering, fields, cause) does not contain the secret, as `TeamsTest` "an answer echoing a managed identity's header keeps none of it" does.

### Transport failures and the live suite

Failures an `HttpServer` cannot produce use raw kyo-net peers: `withCountingPeer` answers each connection with fixed bytes, and `withClosingAfterHead` sends a truncated head and closes. Each raw-tier site carries a `// Unsafe:` comment. To point a call at a raw peer, keep the harness config and replace `serviceHosts` with the raw peer's origin; the harness still serves the token.

`TeamsLiveTest` runs each outbound leaf's one body on one of two targets, on the real clock (`TeamsLiveTest.scala`, the `TeamsLiveTest` scaladoc):

- On Teams when `TEAMS_APP_ID` is set, with `TEAMS_CLIENT_SECRET` and `TEAMS_REFERENCE` (plus `TEAMS_TENANT_ID` for a single-tenant registration); a leaf names the first of those that is missing.
- Otherwise, as in CI, on Microsoft 365 Agents Playground, Microsoft's local Bot Connector emulation, in a kyo-pod container of the leaf's own (`TeamsPlayground.scala`): an image built from `shared/src/test/playground/Containerfile`, the Node image pinned by digest with the package installed at a pinned version. A CI run whose test selection includes kyo-teams builds it before the tests (`scripts/fixture-images.sh`), so no leaf reaches the npm registry; a leaf run where it was not built fails with the `podman build` command that builds it. A new Playground version changes the Containerfile, the tag in `scripts/fixture-images.sh` and `TeamsPlayground.Image` together. The Playground ignores the Authorization header, so the token comes from `TeamsLocal.withLocalOnRealClock`. These leaves are cancelled on Windows; a failing one prints the container's log tail.

A new outbound leaf goes through `withTarget`. A leaf the Playground does not reproduce passes the gap as `realOnly` and is cancelled on the Playground naming it, never naming a missing variable: the Playground answers every DELETE with 501 `DeleteActivityAPINotImplemented`. The inbound leaf reads the Bot Framework's public OpenID metadata and key set and needs no credential, so it runs on every run.

## Pre-submission checklist (kyo-teams)

- [ ] Every outbound request goes through `Connector.call`, or for the token and keys through `Connector.transport`.
- [ ] `Jwt.verify`'s order is unchanged, or a new check sits where Microsoft's order puts it, before the key fetch when it can.
- [ ] No `case _` was added to a match the compiler keeps closed.
- [ ] A new leaf mixes in exactly the traits of the operations that can produce it, and a named API leaf has its `within` arm and its table row.
- [ ] Peer text in a leaf is `bounded`, Microsoft's text is `redact`ed, and no decode leaf keeps the `DecodeException`.
- [ ] A new inbound sum has a `@catchAll` case.
- [ ] A validating `Schema` goes through `init`.
- [ ] Tests drive time with `Clock.withTimeControl` and concurrency with held requests; a served-handler test uses tokens current at every instant. `TeamsLiveTest` alone runs on the real clock.
