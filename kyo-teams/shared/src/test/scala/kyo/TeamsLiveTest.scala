package kyo

import kyo.internal.charset.Utf8

/** The module against Microsoft's real services, opt-in on the environment and cancelled without it:
  *   - `TEAMS_APP_ID` alone runs the inbound leaf, which fetches the Bot Framework's real OpenID metadata and key set;
  *   - `TEAMS_APP_ID`, `TEAMS_CLIENT_SECRET` and `TEAMS_REFERENCE` (a stored `Teams.ConversationReference` as its JSON, from a
  *     conversation the bot is installed in) run the outbound leaves, with `TEAMS_TENANT_ID` for a single-tenant registration.
  *
  * The outbound leaves post to that conversation and delete what they posted. Nothing here runs under time control: the token and the
  * keys are Microsoft's, issued and checked against the real clock.
  */
class TeamsLiveTest extends kyo.test.Test[Any]:

    private def env(name: String)(using Frame): Maybe[String] < Sync =
        System.env[String](name).map(_.filter(_.nonEmpty))

    /** The config and the reference the outbound leaves use, or the name of the first variable that is missing. */
    private def outbound(using Frame): Result[String, (TeamsConfig, Teams.ConversationReference)] < Sync =
        for
            appId     <- env("TEAMS_APP_ID")
            secret    <- env("TEAMS_CLIENT_SECRET")
            reference <- env("TEAMS_REFERENCE")
            tenant    <- env("TEAMS_TENANT_ID")
        yield (appId, secret, reference) match
            case (Present(id), Present(text), Present(json)) =>
                val built =
                    for
                        appId     <- Teams.AppId.init(id)
                        secret    <- Teams.ClientSecret.init(text)
                        tenantId  <- tenant.fold(Result.succeed(Absent))(t => Teams.TenantId.init(t).map(Present(_)))
                        reference <- Json.decode[Teams.ConversationReference](json)
                        config    <- TeamsConfig.init(
                            appId,
                            TeamsConfig.Credential.Secret(secret),
                            tenant = tenantId.fold(TeamsConfig.Tenant.MultiTenant)(TeamsConfig.Tenant.SingleTenant(_))
                        )
                    yield (config, reference)
                built.mapFailure(e => s"the environment does not hold a usable config: ${e.getClass.getSimpleName}")
            case (Absent, _, _) => Result.fail("TEAMS_APP_ID is not set")
            case (_, Absent, _) => Result.fail("TEAMS_CLIENT_SECRET is not set")
            case _              => Result.fail("TEAMS_REFERENCE is not set")

    private def withOutbound[A](test: (TeamsConfig, Teams.ConversationReference) => A < (Async & Abort[Any]))(using
        Frame
    ): A < (Async & Abort[Any]) =
        outbound.map {
            case Result.Success((config, reference)) => test(config, reference)
            case Result.Failure(missing)             => cancel(missing)
            case Result.Panic(ex)                    => Abort.panic(ex)
        }

    "a message is sent, shown typing, updated, replied to and deleted" in {
        withOutbound { (config, reference) =>
            Teams.run(config) {
                for
                    _       <- Teams.typing(reference)
                    sent    <- Teams.send(reference, Teams.Message.Create.text("kyo-teams live suite: send"))
                    updated <- Teams.update(reference, sent, Teams.Message.Create.text("kyo-teams live suite: update"))
                    replied <- Teams.reply(reference, sent, Teams.Message.Create.text("kyo-teams live suite: reply"))
                    _       <- Teams.delete(reference, replied)
                    _       <- Teams.delete(reference, sent)
                yield
                    assert(updated == sent)
                    assert(replied != sent)
                end for
            }
        }
    }

    "members reads the conversation's first page, and member reads one of them back" in {
        withOutbound { (config, reference) =>
            Teams.run(config) {
                Teams.members(reference).map { page =>
                    page.members.headMaybe match
                        case Absent         => fail("the conversation has no members")
                        case Present(first) => Teams.member(reference, first.id).map(member => assert(member.id == first.id))
                }
            }
        }
    }

    "an activity the conversation does not hold is the activity-not-found leaf" in {
        withOutbound { (config, reference) =>
            val missing = Teams.ActivityId.init("1:kyo-teams-live-missing").getOrThrow
            Teams.run(config)(Abort.run[TeamsDeleteFailure](Teams.delete(reference, missing))).map { result =>
                assert(
                    result.failure.exists {
                        case _: TeamsActivityNotFoundException => true
                        case _                                 => false
                    },
                    s"got: $result"
                )
            }
        }
    }

    "the Bot Framework's real metadata and key set are read, and a token no key signed fails at its signature" in {
        env("TEAMS_APP_ID").map {
            case Absent      => cancel("TEAMS_APP_ID is not set")
            case Present(id) =>
                val appId  = Teams.AppId.init(id).getOrThrow
                val secret = TeamsConfig.Credential.Secret(Teams.ClientSecret.init("unused-by-verify").getOrThrow)
                val config = TeamsConfig.init(appId, secret).getOrThrow
                // The kid of a key Microsoft publishes for Teams, read outside the module, so the module's own fetch is what is tested.
                HttpClient.getText(TeamsConfig.OpenIdMetadata).map { metadata =>
                    val jwks = Json.decode[TeamsLiveTest.Metadata](metadata).getOrThrow.jwks_uri
                    HttpClient.withConfig(_.maxResponseLength(4 * 1024 * 1024))(HttpClient.getText(jwks)).map { keys =>
                        val kid = Maybe.fromOption(Json.decode[TeamsLiveTest.KeySet](keys).getOrThrow.keys
                            .collectFirst { case key if key.endorsements.contains("msteams") => key.kid }).getOrElse("")
                        val claims =
                            s"""{"iss":"https://api.botframework.com","aud":"$id","exp":253402300799,"serviceUrl":"https://smba.trafficmanager.net/amer/"}"""
                        def segment(text: String) = Base64.encodeUrl(Utf8.encode(text))
                        val token                 = Chunk(
                            segment(s"""{"alg":"RS256","kid":"$kid"}"""),
                            segment(claims),
                            Base64.encodeUrl(Span.from(Array.fill[Byte](256)(7)))
                        ).mkString(".")
                        val body =
                            Utf8.encode("""{"serviceUrl":"https://smba.trafficmanager.net/amer/","channelId":"msteams"}""")
                        Teams.run(config)(Abort.run[TeamsWebhookVerifyFailure](Teams.Webhook.verify(
                            Present(s"Bearer $token"),
                            body
                        )))
                            .map(result =>
                                assert(kid.nonEmpty && result == Result.fail(TeamsSignatureMismatchException()), s"kid $kid: $result")
                            )
                    }
                }
        }
    }

end TeamsLiveTest

object TeamsLiveTest:
    final case class Metadata(jwks_uri: String) derives Schema
    final case class Key(kid: String, endorsements: Chunk[String] = Chunk.empty) derives Schema
    final case class KeySet(keys: Chunk[Key]) derives Schema
end TeamsLiveTest
