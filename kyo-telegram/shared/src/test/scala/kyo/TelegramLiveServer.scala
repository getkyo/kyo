package kyo

import kyo.schema.rename

/** A telegram-mock-ai container standing in for the Bot API for one leaf, removed when the leaf's `Scope` closes with the directory
  * its configuration was staged in.
  *
  * It holds one bot and one person (`PersonId`) with a private chat of the same id. The person's actions come through the emulator's
  * admin API: [[says]] stores a message in the chat, so the bot can delete it as it would on Telegram, and the other actions inject
  * the update a person's action produces, after storing any message it carries.
  *
  * The emulator numbers bots from 100001 in registration order and ignores the token, so the one bot's token starts with `BotId`, and
  * getMe answers the token's prefix as Telegram does. Its LLM and proactive messages are off, but it still greets the bot from the
  * person, on a goroutine of the bot's first call, and no setting turns that off. `init` makes that first call and confirms the
  * greeting, so a leaf waiting for the person's next message receives what it caused.
  */
final case class TelegramLiveServer(container: Container, botApi: Int, admin: Int, token: Telegram.Token):

    import TelegramLiveServer.*

    def config(using Frame): TelegramConfig =
        HttpUrl.parse(s"http://$Host:$botApi").flatMap(url => TelegramConfig.init(token, baseUrl = url)).getOrThrow

    /** The person sends `text` in their chat; answers the stored message's id. */
    def says(text: String)(using Frame): Telegram.MessageId < (Async & Abort[HttpException | DecodeException]) =
        HttpClient.postText(s"http://$Host:$admin/api/chats/$PersonId/messages", Json.encode(Said(PersonId, text)))
            .map(body => Abort.get(Json.decode[Stored](body)))
            .map(stored => Telegram.MessageId(stored.message_id))

    /** The person replies `text` to `to`. */
    def replies(to: Telegram.Message, text: String)(using Frame): Unit < (Async & Abort[HttpException | DecodeException]) =
        says(text).map(id => inject(ReplyUpdate(Reply(id.value, Person.Human, ChatRef.Private, seconds(to.date), text, plain(to)))))

    /** The person sets `emoji` on `on`. */
    def reacts(on: Telegram.Message, emoji: String)(using Frame): Unit < (Async & Abort[HttpException]) =
        inject(ReactionUpdate(Reaction(
            ChatRef.Private,
            on.id.value,
            Person.Human,
            seconds(on.date),
            Chunk.empty,
            Chunk(Emoji("emoji", emoji))
        )))

    /** The person presses the button of `on` whose callback data is `data`. */
    def presses(on: Telegram.Message, data: String)(using Frame): Unit < (Async & Abort[HttpException]) =
        Random.nextStringAlphanumeric(16).map(id => inject(CallbackUpdate(Callback(id, Person.Human, plain(on), s"$PersonId", data))))

    /** The tail of the container's log, for a leaf that failed. */
    def postMortem(using Frame): String < Async = ContainerPredef.postMortem(container)

    private def inject[U: Schema](update: U)(using Frame): Unit < (Async & Abort[HttpException]) =
        HttpClient.postText(s"http://$Host:$admin/api/bots/${token.value}/updates", Json.encode(update)).unit

end TelegramLiveServer

object TelegramLiveServer:

    val Image: ContainerImage =
        ContainerImage("ghcr.io/skrashevich/telegram-mock-ai@sha256:7e75f8f8d7902072a5e318ffe9f9b93240087601419b82f7137261b41a48d606")

    val Host: String = "127.0.0.1"

    val PersonId: Long = 1001

    val BotId: Long = 100001

    private val Staged = "/kyo-telegram"

    def init(using
        Frame
    ): TelegramLiveServer < (Async & Scope & Abort[ContainerException | FileSystemException | HttpException | DecodeException]) =
        Random.nextStringAlphanumeric(35).map { secret =>
            val token = Telegram.Token.init(s"$BotId:$secret").getOrThrow
            Path.run(Path.tempDir("kyo-telegram-server")).map { directory =>
                Path.run((directory / "config.yaml").write(configYaml(token))).andThen {
                    Container.init(containerConfig(directory)).map { container =>
                        for
                            botApi <- container.mappedPort(8081)
                            admin  <- container.mappedPort(8082)
                            _      <- confirmGreeting(botApi, token)
                        yield TelegramLiveServer(container, botApi, admin, token)
                    }
                }
            }
        }

    // The long poll is the bot's first call, so it starts the greeting and returns once it is queued; the second call confirms it.
    private def confirmGreeting(botApi: Int, token: Telegram.Token)(using Frame): Unit < (Async & Abort[HttpException | DecodeException]) =
        val bot = s"http://$Host:$botApi/bot${token.value}"
        HttpClient.getText(s"$bot/getUpdates?timeout=30").map(body => Abort.get(Json.decode[Pending](body))).map { pending =>
            pending.result.lastMaybe match
                case Present(greeting) => HttpClient.getText(s"$bot/getUpdates?timeout=0&offset=${greeting.update_id + 1}").unit
                case Absent => Abort.panic(new IllegalStateException("telegram-mock-ai sent no greeting within its 30 second poll"))
        }
    end confirmGreeting

    private def containerConfig(directory: Path)(using Frame): Container.Config =
        Container.Config.default
            .copy(image = Image)
            .port(8081, 0)
            .port(8082, 0)
            .requireService(true)
            // The image runs as `nonroot`, and the staged directory is the test process's, mode 700: under a container daemon that
            // keeps file ownership (podman on Linux, CI's), the emulator could not read its config and would exit at once.
            .user("0")
            .bind(directory, Path(Staged), readOnly = true)
            .command("-config", s"$Staged/config.yaml")
            .healthCheck(Container.HealthCheck.init(ready))

    // Both servers start on goroutines after their log lines, so readiness is an answer from each, read from the host.
    private def ready(container: Container)(using Frame): Unit < (Async & Abort[ContainerException]) =
        for
            botApi <- container.mappedPort(8081)
            admin  <- container.mappedPort(8082)
            bot    <- Abort.run[HttpException](HttpClient.getTextResponse(s"http://$Host:$botApi/", failOnError = false))
            health <- Abort.run[HttpException](HttpClient.getText(s"http://$Host:$admin/api/health"))
            _      <-
                if bot.isSuccess && health.isSuccess then Kyo.unit
                else Abort.fail(ContainerHealthCheckException(container.id, s"Bot API: $bot, admin: $health", attempts = 1))
        yield ()

    private def configYaml(token: Telegram.Token): String =
        s"""server:
           |  host: "0.0.0.0"
           |  port: 8081
           |llm:
           |  enabled: false
           |proactive:
           |  enabled: false
           |seed:
           |  generate:
           |    enabled: false
           |  users:
           |    - id: $PersonId
           |      first_name: "${Person.Human.first_name}"
           |  chats:
           |    - id: $PersonId
           |      type: "private"
           |      members: [$PersonId]
           |  bots:
           |    - token: "${token.value}"
           |      username: "kyo_live_bot"
           |      first_name: "${Person.Bot.first_name}"
           |admin:
           |  enabled: true
           |  host: "0.0.0.0"
           |  port: 8082
           |""".stripMargin

    private def seconds(instant: Instant): Long = instant.toDuration.toSeconds

    private def plain(m: Telegram.Message): Plain =
        Plain(m.id.value, Person.Bot, ChatRef.Private, seconds(m.date), m.text.getOrElse(""))

    // The Bot API's JSON for what the admin API takes, as the emulator's Go models read it.

    final private case class Said(user_id: Long, text: String) derives Schema
    final private case class Stored(message_id: Int) derives Schema
    final private case class Queued(update_id: Long) derives Schema
    final private case class Pending(result: Chunk[Queued]) derives Schema

    final private case class Person(id: Long, is_bot: Boolean, first_name: String) derives Schema
    private object Person:
        val Human: Person = Person(PersonId, false, "Kyo Person")
        val Bot: Person   = Person(BotId, true, "Kyo Live")

    final private case class ChatRef(id: Long, @rename("type") kind: String) derives Schema
    private object ChatRef:
        val Private: ChatRef = ChatRef(PersonId, "private")

    final private case class Plain(message_id: Int, from: Person, chat: ChatRef, date: Long, text: String) derives Schema
    final private case class Reply(message_id: Int, from: Person, chat: ChatRef, date: Long, text: String, reply_to_message: Plain)
        derives Schema
    final private case class ReplyUpdate(message: Reply) derives Schema
    final private case class Emoji(@rename("type") kind: String, emoji: String) derives Schema
    final private case class Reaction(
        chat: ChatRef,
        message_id: Int,
        user: Person,
        date: Long,
        old_reaction: Chunk[Emoji],
        new_reaction: Chunk[Emoji]
    ) derives Schema
    final private case class ReactionUpdate(message_reaction: Reaction) derives Schema
    final private case class Callback(id: String, from: Person, message: Plain, chat_instance: String, data: String) derives Schema
    final private case class CallbackUpdate(callback_query: Callback) derives Schema

end TelegramLiveServer
