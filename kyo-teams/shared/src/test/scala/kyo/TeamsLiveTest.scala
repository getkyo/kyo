package kyo

import kyo.internal.Platform
import kyo.internal.charset.Utf8

/** The module against a real Bot Connector, on the real clock. Each outbound leaf has one body and two targets:
  *   - Teams, when `TEAMS_APP_ID` is set, with `TEAMS_CLIENT_SECRET` and `TEAMS_REFERENCE` (a stored `Teams.ConversationReference` as
  *     its JSON, from a conversation the bot is installed in), and `TEAMS_TENANT_ID` for a single-tenant registration; a leaf is
  *     cancelled naming the first of the others that is missing. The leaves post to that conversation and delete what they posted.
  *   - Otherwise, as in CI, the Agents Playground, Microsoft's local Bot Connector emulation ([[TeamsPlayground]]), in a container of the
  *     leaf's own, with the token [[TeamsLocal]] issues. It runs wherever a container runtime does, and is cancelled on Windows.
  *
  * A leaf whose behaviour the Playground does not reproduce asserts Teams', and is cancelled on the Playground naming the gap. The
  * inbound verification against the Bot Framework's real OpenID metadata and key set needs no credential but reaches the internet, so
  * it runs only when `TEAMS_APP_ID` is set; `KeyCacheTest` runs the same verification on every run against a vendored snapshot of those
  * documents, served locally.
  */
class TeamsLiveTest extends kyo.test.Test[Any]:

    // A Playground leaf starts a container that installs its package; a Teams leaf waits on Microsoft's services.
    override protected def timeout: Duration = 3.minutes

    // Container leaves contend on one daemon, so they run one at a time across every suite of the process, as kyo-email's do.
    override def config = super.config.sequential.globallySequential(true)

    // The daemon's HTTP client is scoped to the leaf, so its idle connections do not outlive it and trip the socket leak check.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        HttpClient.init().flatMap(client => HttpClient.let(client)(body))

    private def env(name: String)(using Frame): Maybe[String] < Sync =
        System.env[String](name).map(_.filter(_.nonEmpty))

    /** The config and the reference the Teams leaves use, or the name of the first variable that is missing. */
    private def onTeams(using Frame): Result[String, (TeamsConfig, Teams.ConversationReference)] < Sync =
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

    private def withTeams(test: (TeamsConfig, Teams.ConversationReference) => Unit < (Async & Abort[Any] & Scope))(using
        Frame
    ): Unit < (Async & Abort[Any] & Scope) =
        onTeams.map {
            case Result.Success((config, reference)) => test(config, reference)
            case Result.Failure(missing)             => cancel(missing)
            case Result.Panic(ex)                    => Abort.panic(ex)
        }

    /** Runs `test` on a Playground of its own, with a config that logs in at [[TeamsLocal]] and may send its token to the Playground. A
      * leaf that fails, is interrupted or times out prints the tail of the Playground's log before the container is removed.
      */
    private def withPlayground(test: (TeamsConfig, Teams.ConversationReference) => Unit < (Async & Abort[Any] & Scope))(using
        Frame
    ): Unit < (Async & Abort[Any] & Scope) =
        if Platform.isWindows then
            cancel("the Playground leaves do not run on Windows: its container daemon cannot serve the Linux Node image")
        else
            TeamsLocal.withLocalOnRealClock { local =>
                TeamsPlayground.init.map { playground =>
                    Scope.ensure {
                        case Present(error) =>
                            playground.postMortem.map(log =>
                                Console.printLineErr(s"$name: the leaf ended with $error; Playground log:\n$log")
                            )
                        case Absent => Kyo.unit
                    }.andThen {
                        val config = TeamsConfig.init(
                            TeamsLocal.appId,
                            TeamsConfig.Credential.Secret(Teams.ClientSecret.init(TeamsLocal.secretText).getOrThrow),
                            loginUrl = local.base,
                            serviceHosts = Chunk(playground.origin)
                        ).getOrThrow
                        test(config, playground.reference)
                    }
                }
            }

    /** Where an outbound leaf runs. A Teams conversation keeps what a leaf posts, so a Teams leaf deletes it; the Playground's is the
      * leaf's own and goes with its container.
      */
    private enum Target derives CanEqual:
        case OnTeams, OnPlayground

    /** Runs `test` on Teams when `TEAMS_APP_ID` is set, and on a Playground of its own otherwise. `realOnly` is why the Playground
      * cannot stand in for this leaf, which is then cancelled before a container starts.
      */
    private def withTarget(realOnly: Maybe[String] = Absent)(
        test: (Target, TeamsConfig, Teams.ConversationReference) => Unit < (Async & Abort[Any] & Scope)
    )(using Frame): Unit < (Async & Abort[Any] & Scope) =
        env("TEAMS_APP_ID").map {
            case Present(_) => withTeams((config, reference) => test(Target.OnTeams, config, reference))
            case Absent     =>
                realOnly match
                    case Present(reason) =>
                        cancel(s"the Agents Playground differs from Teams: $reason; set TEAMS_APP_ID to run it on Teams")
                    case Absent => withPlayground((config, reference) => test(Target.OnPlayground, config, reference))
        }

    private val PlaygroundDelete = "it answers every DELETE with 501 DeleteActivityAPINotImplemented"

    "a message is sent, shown typing, updated and replied to" in withTarget() { (target, config, reference) =>
        Teams.run(config) {
            for
                _       <- Teams.typing(reference)
                sent    <- Teams.send(reference, Teams.Message.Create.text("kyo-teams live suite: send"))
                updated <- Teams.edit(reference, sent, Teams.Message.Create.text("kyo-teams live suite: edit"))
                replied <- Teams.reply(reference, sent, Teams.Message.Create.text("kyo-teams live suite: reply"))
                _       <-
                    if target == Target.OnTeams then Teams.delete(reference, replied).andThen(Teams.delete(reference, sent))
                    else Kyo.unit
            yield
                assert(updated == sent)
                assert(replied != sent)
            end for
        }
    }

    "members reads the conversation's first page, and member reads one of them back" in withTarget() { (_, config, reference) =>
        Teams.run(config) {
            Teams.members(reference).map { page =>
                page.members.headMaybe match
                    case Absent         => fail("the conversation has no members")
                    case Present(first) => Teams.member(reference, first.id).map(member => assert(member.id == first.id))
            }
        }
    }

    "a message and its reply are deleted" in {
        withTarget(Present(PlaygroundDelete)) { (_, config, reference) =>
            Teams.run(config) {
                for
                    sent    <- Teams.send(reference, Teams.Message.Create.text("kyo-teams live suite: delete"))
                    replied <- Teams.reply(reference, sent, Teams.Message.Create.text("kyo-teams live suite: delete reply"))
                    deleted <- Abort.run[TeamsDeleteFailure](Teams.delete(reference, replied).andThen(Teams.delete(reference, sent)))
                yield assert(deleted == Result.unit, s"got: $deleted")
            }
        }
    }

    "an activity the conversation does not hold is the activity-not-found leaf" in {
        withTarget(Present(s"$PlaygroundDelete, never 404")) { (_, config, reference) =>
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

    // The metadata and key set are public, and the app id only names the token's audience, so no credential is needed. Reading them
    // reaches the internet, which tests run in regular CI must not, so the leaf runs only where the other Teams leaves do.
    "the Bot Framework's real metadata and key set are read, and a token no key signed fails at its signature" in {
        env("TEAMS_APP_ID").map { fromEnv =>
            val appId =
                fromEnv.fold(cancel("the Bot Framework's real metadata and key set are on the internet; set TEAMS_APP_ID to run it"))(
                    Teams.AppId.init(_).getOrThrow
                )
            val id     = appId.value
            val secret = TeamsConfig.Credential.Secret(Teams.ClientSecret.init("unused-by-verify").getOrThrow)
            val config = TeamsConfig.init(appId, secret).getOrThrow
            // The kid of a key Microsoft publishes for Teams, read outside the module, so the module's own fetch is what is tested.
            HttpClient.getText(TeamsConfig.OpenIdMetadata).map { metadata =>
                val jwks = Json.decode[TeamsLiveTest.Metadata](metadata).getOrThrow.jwks_uri
                HttpClient.withConfig(_.maxResponseLength(4.mib))(HttpClient.getText(jwks)).map { keys =>
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
