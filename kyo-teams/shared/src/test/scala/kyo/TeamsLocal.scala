package kyo

import kyo.charset.Charset
import kyo.internal.charset.Utf8

/** A local peer playing the identity platform, the managed identity endpoint, the Bot Connector, and the OpenID metadata and key set at
  * once.
  *
  * Each request is recorded and answered with the next reply queued under its label, which `label` derives from the method, path and
  * body, the last one repeating; a label with nothing queued holds the request, releasing `held`, until `release`. By default `token`
  * answers a fresh bearer token, `metadata` names this peer's key set, and `jwks` holds `k1` and `s1`. A test's leaf runs under
  * `Clock.withTimeControl`, so no deadline fires unless it advances time; a handler served by a server reads the server fiber's clock
  * instead.
  */
object TeamsLocal:

    // Built from parts: a failure's message quotes the source lines around its frame, which would otherwise show the literal.
    val secretText: String = Chunk("local", "TEST", "secret~value").mkString("-")
    val tokenText: String  = Chunk("eyJlocal", "TEST", "access", "token").mkString(".")

    val appIdText        = "00001111-aaaa-2222-bbbb-3333cccc4444"
    val conversationText = "19:efa9296d959346209fea44151c742e73@thread.skype"

    def appId(using Frame): Teams.AppId = Teams.AppId.init(appIdText).getOrThrow

    def conversation(using Frame): Teams.ConversationId = Teams.ConversationId.init(conversationText).getOrThrow

    /** The conversation id as one path segment. */
    val conversationSegment = "19%3Aefa9296d959346209fea44151c742e73%40thread.skype"

    /** One request the peer received. */
    final case class Seen(
        method: String,
        path: String,
        query: Maybe[String],
        authorization: Maybe[String],
        contentType: Maybe[String],
        identityHeader: Maybe[String],
        body: String
    ) derives CanEqual:
        def label: String = TeamsLocal.label(method, path, body)
    end Seen

    final case class Reply(status: HttpStatus, body: String, headers: Chunk[(String, String)] = Chunk.empty)

    def json(body: String): Reply                    = Reply(HttpStatus.OK, body)
    def resource(id: String): Reply                  = json(s"""{"id":"$id"}""")
    def bearer(token: String, expiresIn: Int): Reply =
        json(s"""{"token_type":"Bearer","expires_in":$expiresIn,"ext_expires_in":$expiresIn,"access_token":"$token"}""")
    inline def error(inline status: Int, code: String, message: String, headers: Chunk[(String, String)] = Chunk.empty): Reply =
        errorWith(HttpStatus(status), code, message, headers)
    def errorWith(status: HttpStatus, code: String, message: String, headers: Chunk[(String, String)]): Reply =
        Reply(status, s"""{"error":{"code":"$code","message":"$message"}}""", headers)

    val Issuer = "https://api.botframework.com"

    /** A modulus kyo-crypto accepts for RS256 (2048 bits, odd) whose factors nobody knows, so no signature verifies under it. */
    val modulus: String =
        Base64.encodeUrl(Span.from(Array.tabulate[Byte](256)(i => if i == 0 then 0x80.toByte else if i == 255 then 1 else 0)))

    /** A key-set entry; `n` defaults to [[modulus]]. */
    def key(
        kid: String,
        n: String = modulus,
        e: String = "AQAB",
        kty: String = "RSA",
        use: String = "\"sig\"",
        endorsements: String = "\"msteams\""
    ): String =
        s"""{"kty":"$kty","use":$use,"kid":"$kid","n":"$n","e":"$e","endorsements":[$endorsements]}"""

    def keySet(keys: String*): Reply = json(keys.mkString("""{"keys":[""", ",", "]}"))

    /** The JSON `text` as one unpadded base64url segment. */
    def segment(text: String): String = Base64.encodeUrl(Utf8.encode(text))

    /** A token of `header` and `claims` whose signature is 256 bytes no key produced. */
    def unsigned(claims: String, header: String = """{"alg":"RS256","kid":"k1","typ":"JWT"}"""): String =
        Chunk(segment(header), segment(claims), Base64.encodeUrl(Span.from(Array.fill[Byte](256)(7)))).mkString(".")

    /** The public modulus of the key that signed [[signed]], held by the default key set as `s1`. */
    val signedModulus: String = TeamsVectors.text("bot-framework-tokens", "modulus")

    /** A token signed offline by `s1`'s key over [[signedClaims]]. */
    val signed: String = TeamsVectors.text("bot-framework-tokens", "token-s1")

    /** A token signed by `s1`'s key over the same claims, naming `k1`, under which its signature does not verify. */
    val signedForOtherKid: String = TeamsVectors.text("bot-framework-tokens", "token-k1")

    /** The claims of [[signed]]: issued at [[signedAt]], valid from Epoch to the end of year 9999, so a served handler, which reads
      * the server fiber's clock, accepts it too.
      */
    val signedClaims: String = TeamsVectors.text("bot-framework-tokens", "claims.json")

    val signedAt: Instant = Instant.Epoch + 1767225600.seconds

    /** The service URL [[signed]]'s `serviceUrl` claim names. */
    val signedServiceUrl = "https://smba.trafficmanager.net/amer/"

    private def label(method: String, path: String, body: String): String =
        val all      = Chunk.from(path.split('/')).filter(_.nonEmpty)
        val segments = all.dropWhile(_ != "v3")
        (method, segments) match
            case _ if all.endsWith(Chunk("oauth2", "v2.0", "token"))                                    => "token"
            case _ if all.startsWith(Chunk("MSI", "token"))                                             => "msi"
            case _ if all.endsWith(Chunk(".well-known", "openidconfiguration"))                         => "metadata"
            case _ if all.endsWith(Chunk(".well-known", "keys"))                                        => "jwks"
            case (_, Chunk("v3", "conversations", _, "custom-route"))                                   => "custom"
            case ("POST", Chunk("v3", "conversations"))                                                 => "create"
            case ("POST", Chunk("v3", "conversations", _, "activities")) if body.contains("\"typing\"") => "typing"
            case ("POST", Chunk("v3", "conversations", _, "activities"))                                => "send"
            case ("POST", Chunk("v3", "conversations", _, "activities", _))                             => "reply"
            case ("PUT", Chunk("v3", "conversations", _, "activities", _))                              => "edit"
            case ("DELETE", Chunk("v3", "conversations", _, "activities", _))                           => "delete"
            case ("GET", Chunk("v3", "conversations", _, "pagedmembers"))                               => "members"
            case ("GET", Chunk("v3", "conversations", _, "members", _))                                 => "member"
            case _                                                                                      => "custom"
        end match
    end label

    final class Local(
        val base: HttpUrl,
        replies: AtomicRef[Map[String, Chunk[Reply]]],
        requests: AtomicRef[Chunk[Seen]],
        val held: Latch,
        private[TeamsLocal] val open: Latch
    ):

        /** Answers every held request with the reply now queued under its label. */
        def release(using Frame): Unit < Sync = open.release

        /** The service URL calls go to: this peer, under `/amer/`. */
        def serviceUrl(using Frame): Teams.ServiceUrl = Teams.ServiceUrl.init(base.copy(path = "/amer/").full).getOrThrow

        /** A reference to the test conversation at this peer. */
        def reference(using Frame): Teams.ConversationReference =
            Teams.ConversationReference(serviceUrl, Teams.ConversationAccount(conversation))

        /** A config with the client secret, logging in and sending to this peer. */
        def config(using Frame): TeamsConfig =
            configWith(TeamsConfig.Credential.Secret(Teams.ClientSecret.init(secretText).getOrThrow))

        def configWith(credential: TeamsConfig.Credential)(using Frame): TeamsConfig =
            TeamsConfig.init(
                appId,
                credential,
                loginUrl = base,
                openIdMetadataUrl = metadataUrl,
                serviceHosts = Chunk(base.copy(path = "/"))
            ).getOrThrow

        /** Where this peer serves the OpenID metadata and the key set. */
        def metadataUrl: HttpUrl = base.copy(path = "/v1/.well-known/openidconfiguration")
        def keysUrl: HttpUrl     = base.copy(path = "/v1/.well-known/keys")

        /** The OpenID metadata naming this peer's key set. */
        def metadata(issuer: String = Issuer, algorithms: String = "\"RS256\"", jwksUri: String = keysUrl.full): Reply =
            json(s"""{"issuer":"$issuer","jwks_uri":"$jwksUri","id_token_signing_alg_values_supported":[$algorithms]}""")

        /** Unsigned claims a delivery's token carries, valid from Epoch for an hour, naming this peer's service URL. */
        def claims(
            iss: String = s"\"$Issuer\"",
            aud: String = s"\"$appIdText\"",
            exp: String = "3600",
            nbf: String = "0",
            serviceUrl: String = s"\"${base.copy(path = "/amer/").full}\""
        ): String =
            Chunk("iss" -> iss, "aud" -> aud, "exp" -> exp, "nbf" -> nbf, "serviceUrl" -> serviceUrl)
                .filter(_._2.nonEmpty).map((k, v) => s""""$k":$v""").mkString("{", ",", "}")

        /** The body of a delivery from Teams at this peer's service URL. */
        def activity(channelId: String = "msteams", serviceUrl: String = base.copy(path = "/amer/").full): Span[Byte] =
            Utf8.encode(
                s"""{"type":"typing","id":"1:a","serviceUrl":"$serviceUrl","channelId":"$channelId","from":{"id":"29:u"},"conversation":{"id":"$conversationText"},"recipient":{"id":"28:b"}}"""
            )

        /** Runs `v` with a client on this peer. */
        def api[A, S](v: A < (S & Env[Teams]))(using Frame): A < (S & Async) = Teams.run(config)(v)

        def reply(label: String, rs: Reply*)(using Frame): Unit < Sync = replies.updateAndGet(_.updated(label, Chunk.from(rs))).unit

        def seen(using Frame): Chunk[Seen] < Sync = requests.get

        def seen(label: String)(using Frame): Chunk[Seen] < Sync = requests.get.map(_.filter(_.label == label))

        private[TeamsLocal] def next(label: String)(using Frame): Maybe[Reply] < Sync =
            replies.getAndUpdate(m => m.get(label).fold(m)(q => if q.size > 1 then m.updated(label, q.drop(1)) else m))
                .map(m => Maybe.fromOption(m.get(label)).flatMap(_.headMaybe))

        private[TeamsLocal] def record(s: Seen)(using Frame): Unit < Sync = requests.updateAndGet(_ :+ s).unit
    end Local

    def withLocal[A](test: Local => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Clock.withTimeControl(_ => serve(HttpServerConfig.default.port(0).host("127.0.0.1"), "http")(test))

    /** [[withLocal]] over TLS with a self-signed certificate, which the default `tls` does not trust. */
    def withLocalTls[A](test: Local => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        Clock.withTimeControl(_ =>
            serve(HttpServerConfig.default.port(0).host("127.0.0.1").tls(kyo.internal.TlsTestHelper.serverTlsConfig), "https")(test)
        )

    /** [[withLocal]] on the real clock, for a live leaf whose other peer is a real server: its request timeouts must be able to fire. */
    def withLocalOnRealClock[A](test: Local => A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        serve(HttpServerConfig.default.port(0).host("127.0.0.1"), "http")(test)

    private def serve[A](serverConfig: HttpServerConfig, scheme: String)(test: Local => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        for
            replies  <- AtomicRef.init(Map("token" -> Chunk(bearer(tokenText, 3600))))
            requests <- AtomicRef.init(Chunk.empty[Seen])
            held     <- Latch.init(1)
            open     <- Latch.init(1)
            recorder = Local(HttpUrl(Present(scheme), "127.0.0.1", 0, "/", Absent), replies, requests, held, open)
            server <- HttpServer.init(serverConfig)(routes(recorder)*)
            local = Local(recorder.base.copy(port = server.port), replies, requests, held, open)
            _      <- local.reply("metadata", local.metadata())
            _      <- local.reply("jwks", keySet(key("k1"), key("s1", n = signedModulus)))
            result <- test(local)
        yield result
    end serve

    private def routes(local: Local)(using Frame): Chunk[HttpHandler[?, ?, ?]] =
        def handle(method: String, url: HttpUrl, headers: HttpHeaders, body: String) =
            val seen = Seen(
                method,
                url.path,
                url.rawQuery,
                headers.get("Authorization"),
                headers.get("Content-Type"),
                headers.get("X-IDENTITY-HEADER"),
                body
            )
            def answer(r: Reply) = r.headers.foldLeft(HttpResponse(r.status).addField("body", r.body))((a, h) => a.addHeader(h._1, h._2))
            local.record(seen).andThen(local.next(seen.label)).map {
                case Present(r) => answer(r)
                case Absent     =>
                    local.held.release.andThen(local.open.await).andThen(local.next(seen.label)).map {
                        case Present(r) => answer(r)
                        case Absent     => Async.never
                    }
            }
        end handle
        val rest     = HttpPath.Capture.Rest("path")
        val withBody = Chunk(HttpRoute.postRaw(rest), HttpRoute.putRaw(rest), HttpRoute.patchRaw(rest)).map(route =>
            route.request(_.bodyBinary).response(_.bodyText).handler(req =>
                handle(req.method.name, req.url, req.headers, Charset.Utf8.decode(req.fields.body))
            )
        )
        val withoutBody = Chunk(HttpRoute.getRaw(rest), HttpRoute.deleteRaw(rest)).map(route =>
            route.response(_.bodyText).handler(req => handle(req.method.name, req.url, req.headers, ""))
        )
        withBody ++ withoutBody
    end routes

    /** A leaf's class and fields, for an assertion's clue. */
    def fieldsOf(e: Any): String =
        e match
            case p: Product => p.productIterator.mkString(s"${p.getClass.getSimpleName}(", ", ", ")")
            case other      => String.valueOf(other)

    /** A leaf's message, its rendering, every field, and its cause's rendering, message and fields, as one string. */
    def rendered(e: Throwable): String =
        def fields(a: Any): String =
            a match
                case p: Product => p.productIterator.mkString("|")
                case other      => String.valueOf(other)
        val cause = Maybe(e.getCause()).fold("")(c => c.toString + "|" + c.getMessage + "|" + fields(c))
        e.getMessage + "|" + e.toString + "|" + fields(e) + "|" + cause
    end rendered

    /** A peer that answers every connection with `response`, queued on accept, and counts the connections it accepted, under
      * `Clock.withTimeControl`.
      */
    def withCountingPeer[A](response: String)(test: (Int, AtomicInt) => A < (Async & Abort[Any] & Scope))(using
        Frame
    ): A < (Async & Abort[Any] & Scope) =
        val bytes = Utf8.encode(response)
        Clock.withTimeControl { _ =>
            AtomicInt.init.map { accepted =>
                Sync.Unsafe.defer {
                    kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16) { conn =>
                        // Unsafe: the accept callback runs outside the effect system; it counts the connection and queues the answer,
                        // which HTTP/1.1 lets a server send before it has read the request.
                        discard(accepted.unsafe.incrementAndGet())
                        discard(conn.outbound.offer(bytes))
                    }
                }.map { fiber =>
                    // Unsafe: the listener is kyo-net's raw tier; it is closed when the test's Scope ends.
                    fiber.safe.use(listener => Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen(test(listener.port, accepted)))
                }
            }
        }
    end withCountingPeer

    /** A raw HTTP/1.1 answer of `status` with a JSON `body`. */
    def raw(status: String, body: String, headers: Chunk[(String, String)] = Chunk.empty): String =
        val extra = headers.map((k, v) => s"$k: $v\r\n").mkString
        s"HTTP/1.1 $status\r\nContent-Type: application/json\r\n${extra}Content-Length: ${Utf8.encode(body).size}\r\n\r\n$body"

    /** A peer that, once `request` is running against it, reads the request, answers a head promising 100 bytes with 6 of them, and
      * closes. kyo-net writes every queued span before it closes the socket, so the client reads the whole head first.
      */
    def withClosingAfterHead[A](request: Int => A < Async)(using Frame): A < (Async & Abort[Any] & Scope) =
        val head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"id\":"
        Clock.withTimeControl { _ =>
            Channel.init[kyo.net.Connection](1).map { accepted =>
                Sync.Unsafe.defer {
                    kyo.net.NetPlatform.transport.listen("127.0.0.1", 0, 16) { conn =>
                        // Unsafe: the accept callback runs outside the effect system; it hands the connection to the test.
                        discard(accepted.unsafe.offer(conn))
                    }
                }.map { fiber =>
                    fiber.safe.use { listener =>
                        // Unsafe: the raw listener and connection have no safe close; each is closed once the test is done with it.
                        Scope.ensure(Sync.Unsafe.defer(listener.close())).andThen {
                            Fiber.initUnscoped(request(listener.port)).map { client =>
                                accepted.take.map { conn =>
                                    Abort.run[Closed](conn.inbound.safe.take)
                                        .andThen(Abort.run[Closed](conn.outbound.safe.put(Utf8.encode(head))))
                                        .andThen(Sync.Unsafe.defer(conn.close()))
                                        .andThen(client.get)
                                }
                            }
                        }
                    }
                }
            }
        }
    end withClosingAfterHead

end TeamsLocal
