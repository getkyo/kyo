package kyo

import kyo.EmailInvalidConfigException.Setting
import kyo.EmailInvalidConfigException.Violation

/** Configuration for the IMAP side: which server to reach, the account to log in to, how to secure the connection, and how a held
  * session keeps itself alive. [[EmailReceive.run]] takes one.
  *
  * `port` defaults from the TLS mode: 993 for [[Email.Tls.Implicit]], 143 for [[Email.Tls.StartTls]]. `connectTimeout` bounds the TCP
  * connect and the TLS handshake, and `commandTimeout` bounds the wait for a command's tagged completion; the first expiring fails with an
  * [[EmailConnectException]] of kind `ConnectTimeout`, the second with an [[EmailTransportException]] of kind `Timeout`.
  *
  * `maxResponseLength` bounds what one command's response may hold: every literal, which is how IMAP carries a message, and every untagged
  * response before the completion, together. A literal announced past what remains is refused before any of it is read; either excess is an
  * [[EmailTransportException]] of kind `Protocol` and closes the connection, since the rest of the response cannot be skipped. A session
  * applies it as at least 1 byte and at most 2147483647 bytes, the most one literal can be read into. The receive loop never asks for a
  * message whose size is past that bound, and delivers `EmailReceive.InboxEvent.Unreadable` with `TooLarge` instead; the fetch of a
  * message within it may also take the two lines around the body, so a message at the limit is read.
  *
  * The last three fields govern a long-running receive loop. `reconnect` is the schedule of delays between attempts to reopen a dropped
  * session; `Schedule.never` stops at the first drop. `idleRenewal` is how long an IDLE runs before the module ends and reissues it, and
  * `pollInterval` the delay between checks for new mail on a server that does not support IDLE. A server may log out a client that sent
  * nothing for 30 minutes, the shortest inactivity timeout RFC 9051 section 5.4 allows it, and RFC 2177 section 3 asks a client to renew an
  * IDLE at least every 29 minutes; both are capped at 29 minutes.
  *
  * `init` validates every field and fails with [[EmailInvalidConfigException]] on an empty host, a port outside 1 to 65535, a TLS
  * `minVersion` above its `maxVersion`, or a duration outside its range, so an invalid config never reaches a connection. A setter named
  * after a field returns the config with that field replaced: through the same check, as a `Result`, for a checked field, and directly
  * for `account`, `maxResponseLength` and `reconnect`, which are never refused.
  *
  * `toString` renders no secret: the account prints its password or token computation redacted.
  *
  * @see
  *   [[kyo.Email.Account]] for the account and its credential
  * @see
  *   [[kyo.Email.Tls]] for the security mode
  * @see
  *   [[kyo.EmailSmtpConfig]] for the sending side
  */
final case class EmailImapConfig private (
    host: String,
    account: Email.Account,
    tls: Email.Tls,
    port: Maybe[Int],
    connectTimeout: Duration,
    commandTimeout: Duration,
    maxResponseLength: ByteSize,
    reconnect: Schedule,
    idleRenewal: Duration,
    pollInterval: Duration
):

    /** The port to connect to: `port` when present, otherwise the default for the TLS mode. */
    def resolvedPort: Int =
        port.getOrElse(tls match
            case _: Email.Tls.Implicit => 993
            case _: Email.Tls.StartTls => 143)

    /** This config with `host`, or the violation. */
    def host(host: String)(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        EmailImapConfig.checked(copy(host = host))

    /** This config logging in to `account`. */
    def account(account: Email.Account): EmailImapConfig = copy(account = account)

    /** This config with `tls`, or the violation. */
    def tls(tls: Email.Tls)(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        EmailImapConfig.checked(copy(tls = tls))

    /** This config with `port`, or the violation. */
    def port(port: Maybe[Int])(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        EmailImapConfig.checked(copy(port = port))

    /** This config with `connectTimeout`, or the violation. */
    def connectTimeout(connectTimeout: Duration)(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        EmailImapConfig.checked(copy(connectTimeout = connectTimeout))

    /** This config with `commandTimeout`, or the violation. */
    def commandTimeout(commandTimeout: Duration)(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        EmailImapConfig.checked(copy(commandTimeout = commandTimeout))

    /** This config bounding one response by `maxResponseLength`. */
    def maxResponseLength(maxResponseLength: ByteSize): EmailImapConfig = copy(maxResponseLength = maxResponseLength)

    /** This config reconnecting on `reconnect`. */
    def reconnect(reconnect: Schedule): EmailImapConfig = copy(reconnect = reconnect)

    /** This config with `idleRenewal`, or the violation. */
    def idleRenewal(idleRenewal: Duration)(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        EmailImapConfig.checked(copy(idleRenewal = idleRenewal))

    /** This config with `pollInterval`, or the violation. */
    def pollInterval(pollInterval: Duration)(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        EmailImapConfig.checked(copy(pollInterval = pollInterval))
end EmailImapConfig

object EmailImapConfig:

    /** A config for `host` logging in to `account`, or the first violation among its fields. */
    def init(
        host: String,
        account: Email.Account,
        tls: Email.Tls = Email.Tls.Implicit,
        port: Maybe[Int] = Absent,
        connectTimeout: Duration = 30.seconds,
        commandTimeout: Duration = 1.minute,
        maxResponseLength: ByteSize = defaultMaxResponseLength,
        reconnect: Schedule = defaultReconnect,
        idleRenewal: Duration = 29.minutes,
        pollInterval: Duration = 1.minute
    )(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        checked(
            new EmailImapConfig(
                host,
                account,
                tls,
                port,
                connectTimeout,
                commandTimeout,
                maxResponseLength,
                reconnect,
                idleRenewal,
                pollInterval
            )
        )

    /** 150 MiB: Microsoft 365's largest message, the highest limit of the major providers (Gmail's is 50 MB), so the default refuses no
      * message such an account holds.
      */
    val defaultMaxResponseLength: ByteSize = 150.mib

    /** Exponential backoff from one second, doubling to at most five minutes, retried without end. */
    val defaultReconnect: Schedule = Schedule.exponentialBackoff(1.second, 2.0, 5.minutes)

    /** The longest `idleRenewal` accepted: RFC 2177 section 3's 29 minutes, inside the 30 minute inactivity timeout RFC 9051 section 5.4
      * allows a server.
      */
    val maxIdleRenewal: Duration = 29.minutes

    /** The longest `pollInterval` accepted: between polls the connection is silent, so a poll must come inside the same 30 minutes. */
    val maxPollInterval: Duration = 29.minutes

    private def checked(config: EmailImapConfig)(using Frame): Result[EmailInvalidConfigException, EmailImapConfig] =
        validate(config) match
            case Present(violation) => Result.fail(EmailInvalidConfigException(violation))
            case Absent             => Result.succeed(config)

    private def validate(config: EmailImapConfig): Maybe[Violation] =
        if config.host.isEmpty then Present(Violation.EmptyHost)
        else
            config.port.filter(p => p < 1 || p > 65535).map(Violation.PortOutOfRange(_))
                .orElse(EmailInvalidConfigException.checkTls(config.tls))
                .orElse(EmailInvalidConfigException.checkTimeout(Setting.ConnectTimeout, config.connectTimeout))
                .orElse(EmailInvalidConfigException.checkTimeout(Setting.CommandTimeout, config.commandTimeout))
                .orElse(
                    if config.idleRenewal <= Duration.Zero || config.idleRenewal > maxIdleRenewal then
                        Present(Violation.DurationOutOfRange(Setting.IdleRenewal, config.idleRenewal))
                    else Absent
                )
                .orElse(
                    if config.pollInterval <= Duration.Zero || config.pollInterval > maxPollInterval then
                        Present(Violation.DurationOutOfRange(Setting.PollInterval, config.pollInterval))
                    else Absent
                )
    end validate

end EmailImapConfig
