package kyo

import kyo.net.TlsTestCertShared

/** A docker-mailserver container for one leaf: Postfix for submission and delivery, Dovecot for IMAP, so a message submitted over SMTP is
  * delivered by real server software and read back over IMAP. It is removed when the leaf's `Scope` closes, with the directory its
  * configuration was staged in.
  *
  * The image writes into its configuration directory while it starts, so the staged files are bound read-only at `/kyo-email` and copied
  * into place before supervisord starts. Every server refuses an unknown recipient at `RCPT` (the image's submission services accept it
  * and bounce later), carries SMTPUTF8 from submission to the mailbox, and refuses a message past [[SizeLimit]]. Each
  * [[EmailLiveServer.User]] gets the capabilities its userdb fields leave it, so one server offers MOVE to one account and not to another.
  * The accounts share [[password]], drawn at random for each server.
  *
  * XOAUTH2 is served by Dovecot validating a token itself, as an HS256 JWT, so no authorization server is involved; Postfix authenticates
  * submissions through Dovecot, so SMTP accepts the same tokens. The container draws the signing key when it starts, and a second key no
  * validation knows; [[token]] mints a token with the container's `openssl`, so no key or token exists outside it.
  *
  * A [[EmailLiveServer.Kind.Strict]] server accepts TLS 1.3 only and requires a client certificate issued by [[certificate]]: Dovecot
  * refuses the login without one, and Postfix closes the session. Dovecot requires it for IMAP only, since it also authenticates SMTP
  * submissions for Postfix and never sees the SMTP client's certificate.
  *
  * The server's certificate is kyo-net's localhost test certificate, issued to `127.0.0.1`, which is also the CA the clients trust and the
  * client certificate a strict server accepts.
  *
  * Nothing in it reaches the internet: a CI run that selects this module pulls the image at its pinned digest before the tests
  * (`scripts/fixture-images.sh`), [[EmailLiveServer.init]] fails with the command that pulls it rather than pulling where it is missing, and every service of the image
  * that would call out is off.
  */
final case class EmailLiveServer(container: Container, ports: EmailLiveServer.Ports, certificate: Path, key: Path, password: String):

    import EmailLiveServer.*

    def clientCertificate: Email.Tls.ClientCertificate = Email.Tls.ClientCertificate(certificate, key)

    def tls(
        security: Security,
        clientCertificate: Maybe[Email.Tls.ClientCertificate] = Absent,
        minVersion: Email.Tls.Version = Email.Tls.Version.TLS12,
        maxVersion: Email.Tls.Version = Email.Tls.Version.TLS13
    ): Email.Tls =
        val trust = Email.Tls.Trust.CaFile(certificate)
        security match
            case Security.Implicit => Email.Tls.Implicit(trust, minVersion, maxVersion, clientCertificate)
            case Security.StartTls => Email.Tls.StartTls(trust, minVersion, maxVersion, clientCertificate)
    end tls

    def imap(user: User, tls: Email.Tls = tls(Security.Implicit))(using Frame): EmailImapConfig =
        val port = tls match
            case _: Email.Tls.Implicit => ports.imaps
            case _: Email.Tls.StartTls => ports.imap
        EmailLiterals.valid(EmailImapConfig.init(
            Host,
            EmailLiterals.passwordAccountOf(user.login, EmailLiterals.passwordOf(password)),
            tls,
            Present(port)
        ))
    end imap

    def smtp(user: User, tls: Email.Tls = tls(Security.Implicit))(using Frame): EmailSmtpConfig =
        val port = tls match
            case _: Email.Tls.Implicit => ports.submissions
            case _: Email.Tls.StartTls => ports.submission
        EmailLiterals.valid(EmailSmtpConfig.init(
            Host,
            EmailLiterals.passwordAccountOf(user.login, EmailLiterals.passwordOf(password)),
            tls,
            Present(port)
        ))
    end smtp

    def account(user: User)(using Frame): EmailLiveAccount = EmailLiveAccount(user.address, imap(user), smtp(user))

    /** A JWT for `user` of the given kind, minted in the container. */
    def token(user: User, kind: Token)(using Frame): Email.OAuthToken < (Async & Abort[ContainerException]) =
        run("sh", Staged + "/" + MintToken, user.login, kind.toString).map(minted => EmailLiterals.tokenOf(minted.trim))

    /** Runs Dovecot's admin tool in the container and answers its output. */
    def doveadm(args: String*)(using Frame): String < (Async & Abort[ContainerException]) = run(("doveadm" +: args)*)

    /** The tail of the container's log, for a leaf that failed. */
    def postMortem(using Frame): String < Async = ContainerPredef.postMortem(container)

    // A command that exits non-zero fails the leaf with its error rather than letting the leaf read an empty answer.
    private def run(command: String*)(using Frame): String < (Async & Abort[ContainerException]) =
        container.exec(command*).map { result =>
            if result.isSuccess then result.stdout
            else Abort.panic(new IllegalStateException(s"${command.mkString(" ")} exited ${result.exitCode}: ${result.stderr}"))
        }

end EmailLiveServer

object EmailLiveServer:

    /** docker-mailserver 16.0.1, pinned by the digest of its multi-arch index (linux/amd64 and linux/arm64), which
      * `scripts/fixture-images.sh` pulls; the two change together.
      */
    val Image: ContainerImage =
        ContainerImage("docker.io/mailserver/docker-mailserver@sha256:d0fe7668defe157aad57ea31b1707ad1e2fb57d7a91bdf17cbdf876549946c86")

    val Host: String = "127.0.0.1"

    val SizeLimit: ByteSize = 1.mib

    enum Kind derives CanEqual:
        case Standard, Strict

    enum Security derives CanEqual:
        case Implicit, StartTls

    /** What [[EmailLiveServer.token]] mints: a token the server accepts, one signed with the key it does not know, or one past its `exp`. */
    enum Token derives CanEqual:
        case Valid, Foreign, Expired

    /** The accounts every server holds, each with the userdb fields that shape its IMAP capabilities. */
    enum User(val name: String, val userdb: String) derives CanEqual:
        case Test   extends User("test", "")
        case Other  extends User("other", "")
        case NoMove extends User("nomove", "userdb_imap_capability/MOVE=no")
        case Bare   extends User("bare", "userdb_imap_capability/MOVE=no userdb_imap_capability/UIDPLUS=no")

        def login: String = s"$name@example.com"

        def address(using Frame): Email.Address = Email.Address(login)
    end User

    /** The host ports the container's IMAP (143, 993) and submission (587, 465) ports are published on. */
    final case class Ports(imap: Int, imaps: Int, submission: Int, submissions: Int)

    def init(kind: Kind = Kind.Standard)(using
        Frame
    ): EmailLiveServer < (Async & Scope & Abort[ContainerException | FileSystemException]) =
        pulled.andThen(Random.nextStringAlphanumeric(24)).map { password =>
            Path.run {
                Path.tempDir("kyo-email-server").map { directory =>
                    Kyo.foreachDiscard(files(kind, password))((name, content) => (directory / name).write(content)).andThen(directory)
                }
            }.map { directory =>
                Container.init(containerConfig(directory)).map { container =>
                    for
                        imap        <- container.mappedPort(143)
                        imaps       <- container.mappedPort(993)
                        submission  <- container.mappedPort(587)
                        submissions <- container.mappedPort(465)
                    yield EmailLiveServer(
                        container,
                        Ports(imap, imaps, submission, submissions),
                        directory / "cert.pem",
                        directory / "key.pem",
                        password
                    )
                }
            }
        }

    // kyo-pod pulls a missing image on its own, which would reach the registry from inside the leaf.
    private def pulled(using Frame): Unit < (Async & Abort[ContainerException]) =
        Abort.run[ContainerException](ContainerImage.inspect(Image)).map {
            case Result.Success(_)                                 => Kyo.unit
            case Result.Failure(_: ContainerImageMissingException) =>
                Abort.panic(new IllegalStateException(s"${Image.reference} is not pulled; run: podman pull ${Image.reference}"))
            case Result.Failure(other) => Abort.fail(other)
            case Result.Panic(e)       => Abort.panic(e)
        }

    private def files(kind: Kind, password: String): Chunk[(String, String)] =
        val strict   = kind == Kind.Strict
        val accounts =
            User.values.map(user => s"${user.login}|{PLAIN}$password" + (if user.userdb.isEmpty then "" else s"|${user.userdb}"))
        val postfixMain =
            // The reverse lookup of each client would send a DNS query out of the container.
            Chunk("smtputf8_enable = yes", s"message_size_limit = ${SizeLimit.toBytes}", "smtpd_peername_lookup = no") ++
                (if strict then
                     Chunk(
                         s"smtpd_tls_CAfile = $Staged/cert.pem",
                         "smtpd_tls_protocols = >=TLSv1.3",
                         "smtpd_tls_mandatory_protocols = >=TLSv1.3"
                     )
                 else Chunk.empty)
        val postfixMaster =
            Chunk("submission", "submissions").flatMap { service =>
                Chunk(s"$service/inet/smtpd_reject_unlisted_recipient=yes") ++
                    (if strict then Chunk(s"$service/inet/smtpd_tls_ask_ccert=yes", s"$service/inet/smtpd_tls_req_ccert=yes")
                     else Chunk.empty)
            }
        // Without it Dovecot's LMTP does not offer SMTPUTF8, and Postfix bounces every message submitted with it.
        val dovecot = Chunk("protocol lmtp {", "  mail_utf8_extensions = yes", "}") ++
            Chunk(
                "auth_mechanisms = plain login xoauth2",
                "passdb oauth2 {",
                "  driver = oauth2",
                "  mechanisms_filter = xoauth2",
                "}",
                // The oauth2 driver reads these at the top level only; inside the passdb block it reports them missing.
                "oauth2_introspection_mode = local",
                "oauth2_username_attribute = sub",
                "oauth2_local_validation {",
                "  dict fs {",
                "    fs posix {",
                s"      prefix = $OAuthKeys/",
                "    }",
                "  }",
                "}"
            ) ++
            (if strict then
                 Chunk(
                     "ssl_min_protocol = TLSv1.3",
                     "ssl_server {",
                     "  request_client_cert = yes",
                     s"  ca_file = $Staged/cert.pem",
                     // The CA file carries no CRL, and Dovecot rejects every client certificate as untrusted without one.
                     "  require_crl = no",
                     "}",
                     "protocol imap {",
                     "  auth_ssl_require_client_cert = yes",
                     "}"
                 )
             else Chunk.empty)
        Chunk(
            "postfix-accounts.cf" -> lines(Chunk.from(accounts)),
            "postfix-main.cf"     -> lines(postfixMain),
            "postfix-master.cf"   -> lines(postfixMaster),
            "dovecot.cf"          -> lines(dovecot),
            "cert.pem"            -> TlsTestCertShared.certPem,
            "key.pem"             -> TlsTestCertShared.keyPem,
            MintToken             -> mintToken
        )
    end files

    private val Staged = "/tmp/docker-mailserver"

    // Dovecot looks a token's key up at `<prefix>/<azp>/<alg>/<kid>`, `default` standing for an absent claim.
    private val OAuthKeys = "/tmp/oauth2-keys"

    private val SigningKey = s"$OAuthKeys/default/HS256/default"

    private val ForeignKey = "/tmp/oauth2-foreign.key"

    private val MintToken = "mint-token.sh"

    // Keys are base64, as Dovecot reads them; the HMAC takes their bytes as hex. An expired token was issued two hours ago and expired one
    // hour ago, both on the container's clock, which is the clock that validates it.
    private val mintToken =
        s"""b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
           |now=$$(date +%s)
           |case "$$2" in
           |  ${Token.Foreign}) key=$ForeignKey; iat=$$now; exp=$$((now + 3600)) ;;
           |  ${Token.Expired}) key=$SigningKey; iat=$$((now - 7200)); exp=$$((now - 3600)) ;;
           |  *) key=$SigningKey; iat=$$now; exp=$$((now + 3600)) ;;
           |esac
           |hex=$$(openssl base64 -d -A < "$$key" | od -An -tx1 | tr -d ' \\n')
           |h=$$(printf '%s' '{"alg":"HS256","typ":"JWT"}' | b64url)
           |p=$$(printf '{"sub":"%s","iat":%s,"nbf":%s,"exp":%s}' "$$1" "$$iat" "$$iat" "$$exp" | b64url)
           |s=$$(printf '%s' "$$h.$$p" | openssl dgst -sha256 -mac HMAC -macopt "hexkey:$$hex" -binary | b64url)
           |printf '%s.%s.%s' "$$h" "$$p" "$$s"
           |""".stripMargin

    private def lines(all: Chunk[String]): String = all.map(_ + "\n").mkString

    private def containerConfig(directory: Path)(using Frame): Container.Config =
        Container.Config.default
            .copy(image = Image)
            .hostname("mail.example.com")
            // Each service that would reach the internet is off. ENABLE_UPDATE_CHECK defaults to 1 on a release image and asks GitHub
            // for the latest release at start; ClamAV's and SpamAssassin's cron updates are removed with their services.
            .envAll(Dict.from(Map(
                "ENABLE_UPDATE_CHECK" -> "0",
                "ENABLE_CLAMAV"       -> "0",
                "ENABLE_SPAMASSASSIN" -> "0",
                "ENABLE_RSPAMD"       -> "0",
                "ENABLE_AMAVIS"       -> "0",
                "ENABLE_FAIL2BAN"     -> "0",
                "ENABLE_POSTGREY"     -> "0",
                "ENABLE_OPENDKIM"     -> "0",
                "ENABLE_OPENDMARC"    -> "0",
                "ENABLE_POLICYD_SPF"  -> "0",
                "PERMIT_DOCKER"       -> "none",
                "SSL_TYPE"            -> "manual",
                "SSL_CERT_PATH"       -> s"$Staged/cert.pem",
                "SSL_KEY_PATH"        -> s"$Staged/key.pem"
            )))
            .port(143, 0)
            .port(993, 0)
            .port(587, 0)
            .port(465, 0)
            .requireService(true)
            .bind(directory, Path("/kyo-email"), readOnly = true)
            .command(
                "sh",
                "-c",
                Chunk(
                    s"mkdir -p $Staged ${SigningKey.stripSuffix("/default")}",
                    s"cp /kyo-email/* $Staged/",
                    s"openssl rand -base64 32 | tr -d '\\n' > $SigningKey",
                    s"openssl rand -base64 32 | tr -d '\\n' > $ForeignKey",
                    "exec supervisord -c /etc/supervisor/supervisord.conf"
                ).mkString(" && ")
            )
            // Supervisord reports a service RUNNING before it listens, so readiness also connects to each mail port from inside.
            .healthCheck(ContainerPredef.readinessLoop(Chunk(
                "bash",
                "-c",
                "dms-healthcheck && for p in 143 993 587 465; do (exec 3<>/dev/tcp/127.0.0.1/$p) || exit 1; done"
            )))

end EmailLiveServer
