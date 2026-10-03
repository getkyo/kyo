package kyo

/** Values a test knows to be valid, built through the checked constructors: a violation is a defect in the test itself. */
object SlackLiterals:

    def valid[E, A](checked: Result[E, A]): A =
        checked match
            case Result.Success(value) => value
            case other                 => throw new AssertionError(s"the test literal is invalid: $other")

    /** The failure of `checked`, failing the test when it succeeded. */
    def refused[E, A](checked: Result[E, A]): E =
        checked match
            case Result.Failure(failure) => failure
            case other                   => throw new AssertionError(s"expected a failure, got $other")

    def appLevelOf(value: String)(using Frame): SlackToken.AppLevel = valid(SlackToken.AppLevel.init(value))

    def botOf(value: String)(using Frame): SlackToken.Bot = valid(SlackToken.Bot.init(value))

    def methodOf(name: String)(using Frame): SlackMethod = valid(SlackMethod.init(name))

    def rawOf(json: String)(using Frame): SlackBlock.Raw = valid(SlackBlock.Raw.init(json))

    def urlOf(text: String)(using Frame): HttpUrl = valid(HttpUrl.parse(text))

    def configOf(
        appLevel: SlackToken.AppLevel,
        bot: SlackToken.Bot,
        keepAliveInterval: Maybe[Duration] = Present(30.seconds),
        ackDeadline: Duration = 2500.millis,
        reconnect: SlackConfig.Reconnect = SlackConfig.Reconnect.Overlap,
        baseUrl: HttpUrl = SlackConfig.SlackApi,
        requestTimeout: Duration = 10.seconds,
        connectTimeout: Duration = 10.seconds,
        maxResponseLength: ByteSize = 16.mb
    )(using Frame): SlackConfig =
        valid(SlackConfig.init(
            appLevel,
            bot,
            keepAliveInterval,
            ackDeadline,
            reconnect,
            baseUrl,
            requestTimeout,
            connectTimeout,
            maxResponseLength
        ))

end SlackLiterals
