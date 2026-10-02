package kyo

import kyo.EmailInvalidConfigException.Setting
import kyo.EmailInvalidConfigException.Violation
import kyo.internal.Ascii

/** Configuration for the SMTP side: which submission server to reach, how to secure and authenticate the connection, and the name the
  * client announces.
  *
  * Each submission opens its own connection, sends, and closes it, so there is no held session and no reconnect policy here. `retry`,
  * `Absent` by default, is the [[Schedule]] on which `send` and `reply` try again after a transient reply: a `4xx` refusal (RFC 5321
  * section 4.2.1, the action did not occur), every recipient refused with `4xx`, or a `421` closing the channel. Each attempt opens a new
  * connection and computes the credential again; a permanent refusal, an authentication refusal, a lost connection and a failed token
  * computation are never retried, nor is `custom`. When the schedule ends, or its next delay is longer than `retryMaxDelay` (60 seconds by
  * default), the send fails at once with the last failure. `port`
  * defaults from the TLS mode: 465 for [[Email.Tls.Implicit]], 587 for [[Email.Tls.StartTls]] (RFC 8314, RFC 6409). `connectTimeout` bounds
  * the TCP connect and the TLS handshake, and `commandTimeout` bounds the wait for each reply; the first expiring fails with an
  * [[EmailConnectException]] of kind `ConnectTimeout`, the second with an [[EmailTransportException]] of kind `Timeout`.
  *
  * `clientName` is what the client announces with `EHLO`. RFC 5321 section 2.3.5 requires a fully qualified domain name or an address
  * literal, and section 4.1.1.1 has a client without a meaningful name send an address literal, so the default is `[127.0.0.1]`. A host
  * with a public name should set it here.
  *
  * `init` validates every field and fails with [[EmailInvalidConfigException]] on an empty host, a `clientName` that is neither a domain
  * nor an address literal in the RFC 5321 section 4.1.2 grammar, a port outside 1 to 65535, a TLS `minVersion` above its `maxVersion`, or
  * a non-positive timeout or `retryMaxDelay`, so an invalid config never reaches a connection. A setter named after a field returns the
  * config with that field replaced: through the same check, as a `Result`, for a checked field, and directly for `auth` and `retry`, which
  * have no range.
  *
  * `toString` renders no secret: the authentication prints its password or token computation redacted.
  *
  * @tparam E
  *   the failure of the OAuth token computation, `Nothing` for a password; see [[Email.Auth]]
  * @see
  *   [[kyo.Email.Auth]] for the credential
  * @see
  *   [[kyo.Email.Tls]] for the security mode
  * @see
  *   [[kyo.EmailImapConfig]] for the receiving side
  */
final case class EmailSmtpConfig[+E] private (
    host: String,
    auth: Email.Auth[E],
    tls: Email.Tls,
    port: Maybe[Int],
    connectTimeout: Duration,
    commandTimeout: Duration,
    clientName: String,
    retry: Maybe[Schedule],
    retryMaxDelay: Duration
):

    /** The port to connect to: `port` when present, otherwise the default for the TLS mode. */
    def resolvedPort: Int =
        port.getOrElse(tls match
            case _: Email.Tls.Implicit => 465
            case _: Email.Tls.StartTls => 587)

    /** This config with `host`, or the violation. */
    def host(host: String)(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        EmailSmtpConfig.checked(copy(host = host))

    /** This config authenticating with `auth`. */
    def auth[E2](auth: Email.Auth[E2]): EmailSmtpConfig[E2] = copy(auth = auth)

    /** This config with `tls`, or the violation. */
    def tls(tls: Email.Tls)(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        EmailSmtpConfig.checked(copy(tls = tls))

    /** This config with `port`, or the violation. */
    def port(port: Maybe[Int])(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        EmailSmtpConfig.checked(copy(port = port))

    /** This config with `connectTimeout`, or the violation. */
    def connectTimeout(connectTimeout: Duration)(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        EmailSmtpConfig.checked(copy(connectTimeout = connectTimeout))

    /** This config with `commandTimeout`, or the violation. */
    def commandTimeout(commandTimeout: Duration)(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        EmailSmtpConfig.checked(copy(commandTimeout = commandTimeout))

    /** This config announcing `clientName`, or the violation. */
    def clientName(clientName: String)(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        EmailSmtpConfig.checked(copy(clientName = clientName))

    /** This config retrying a transient failure of `send` and `reply` on `retry`, or never with `Absent`. */
    def retry(retry: Maybe[Schedule]): EmailSmtpConfig[E] = copy(retry = retry)

    /** This config with `retryMaxDelay`, or the violation. */
    def retryMaxDelay(retryMaxDelay: Duration)(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        EmailSmtpConfig.checked(copy(retryMaxDelay = retryMaxDelay))
end EmailSmtpConfig

object EmailSmtpConfig:

    /** The IPv4 loopback address literal, which RFC 5321 section 4.1.1.1 has a client without a meaningful name send. */
    val defaultClientName: String = "[127.0.0.1]"

    /** A config for `host` authenticating with `auth`, or the first violation among its fields. */
    def init[E](
        host: String,
        auth: Email.Auth[E],
        tls: Email.Tls = Email.Tls.Implicit,
        port: Maybe[Int] = Absent,
        connectTimeout: Duration = 30.seconds,
        commandTimeout: Duration = 5.minutes,
        clientName: String = defaultClientName,
        retry: Maybe[Schedule] = Absent,
        retryMaxDelay: Duration = 60.seconds
    )(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        checked(new EmailSmtpConfig(host, auth, tls, port, connectTimeout, commandTimeout, clientName, retry, retryMaxDelay))

    private def checked[E](config: EmailSmtpConfig[E])(using Frame): Result[EmailInvalidConfigException, EmailSmtpConfig[E]] =
        validate(config) match
            case Present(violation) => Result.fail(EmailInvalidConfigException(violation))
            case Absent             => Result.succeed(config)

    private def validate(config: EmailSmtpConfig[?]): Maybe[Violation] =
        if config.host.isEmpty then Present(Violation.EmptyHost)
        else if !isDomain(config.clientName) && !isAddressLiteral(config.clientName) then
            Present(Violation.InvalidClientName(config.clientName))
        else
            config.port.filter(p => p < 1 || p > 65535).map(Violation.PortOutOfRange(_))
                .orElse(EmailInvalidConfigException.checkTls(config.tls))
                .orElse(EmailInvalidConfigException.checkTimeout(Setting.ConnectTimeout, config.connectTimeout))
                .orElse(EmailInvalidConfigException.checkTimeout(Setting.CommandTimeout, config.commandTimeout))
                .orElse(EmailInvalidConfigException.checkTimeout(Setting.RetryMaxDelay, config.retryMaxDelay))

    // Let-dig [Ldh-str]: letters, digits and hyphens, beginning and ending with a letter or digit.
    private def isSubDomain(label: String): Boolean =
        label.nonEmpty && Ascii.isAlphaNumeric(label.head) && Ascii.isAlphaNumeric(label.last) &&
            label.forall(c => Ascii.isAlphaNumeric(c) || c == '-')

    private def isDomain(text: String): Boolean =
        text.nonEmpty && text.split("\\.", -1).forall(isSubDomain)

    private def isAddressLiteral(text: String): Boolean =
        text.length > 2 && text.head == '[' && text.last == ']' && {
            val inner = text.substring(1, text.length - 1)
            if Ascii.equalsIgnoreCase(inner.take(5), "IPv6:") then isIpv6(inner.substring(5))
            else if inner.contains(':') then isGeneralLiteral(inner)
            else isIpv4(inner)
        }

    private def isIpv4(text: String): Boolean =
        val parts = text.split("\\.", -1)
        parts.length == 4 && parts.forall(p => p.length <= 3 && Ascii.parseDigits(p).exists(_ <= 255))

    // IPv6-full, IPv6-comp, IPv6v4-full and IPv6v4-comp: 1 to 4 hex digits per group and at most one "::". Without "::" there are 8
    // groups, an IPv4 tail counting as two. With "::" at most 6 groups may appear besides it, or 4 besides it and an IPv4 tail.
    private def isIpv6(text: String): Boolean =
        val (head, tailGroups) =
            val lastColon = text.lastIndexOf(':')
            if lastColon >= 0 && text.substring(lastColon + 1).contains('.') then
                if isIpv4(text.substring(lastColon + 1)) then (text.substring(0, lastColon + 1), 2) else ("", -1)
            else (text, 0)
        end val
        if tailGroups < 0 then false
        else
            val compressed = head.indexOf("::")
            if compressed != head.lastIndexOf("::") then false
            else if compressed < 0 then
                val body   = if tailGroups > 0 then head.dropRight(1) else head
                val groups = body.split(":", -1)
                groups.forall(isHexGroup) && groups.length + tailGroups == 8
            else
                val left        = head.substring(0, compressed)
                val right       = head.substring(compressed + 2)
                val rightBody   = if tailGroups > 0 && right.nonEmpty then right.dropRight(1) else right
                val leftGroups  = if left.isEmpty then Array.empty[String] else left.split(":", -1)
                val rightGroups = if rightBody.isEmpty then Array.empty[String] else rightBody.split(":", -1)
                (leftGroups ++ rightGroups).forall(isHexGroup) && leftGroups.length + rightGroups.length <= 6 - tailGroups
            end if
        end if
    end isIpv6

    private def isHexGroup(group: String): Boolean =
        group.nonEmpty && group.length <= 4 && group.forall(Ascii.isHexDigit)

    // Standardized-tag ":" 1*dcontent, where dcontent is printable US-ASCII other than "[", "\" and "]".
    private def isGeneralLiteral(text: String): Boolean =
        val colon = text.indexOf(':')
        val tag   = text.substring(0, colon)
        val body  = text.substring(colon + 1)
        isSubDomain(tag) && body.nonEmpty && body.forall(c => (c >= 33 && c <= 90) || (c >= 94 && c <= 126))
    end isGeneralLiteral

end EmailSmtpConfig
