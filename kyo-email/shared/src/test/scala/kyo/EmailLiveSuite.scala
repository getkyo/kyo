package kyo

import kyo.internal.Platform

/** The live suites: leaves against real mail servers, on the real clock. A leaf runs under accounts of its own on a server the leaves share
  * ([[server]]), so a leaf that disconnects its sessions or recreates its mailboxes cannot disturb another, or on a server of its own when
  * what it does would reach the leaves after it ([[ownServer]]).
  */
abstract class EmailLiveSuite extends kyo.test.Test[Any]:

    override protected def timeout: Duration = EmailLiveAccount.Timeout

    // Container leaves contend on one daemon, so they run one at a time across every suite of the process, as kyo-sql's do.
    override def config = super.config.sequential.globallySequential(true)

    // The daemon's HTTP client is scoped to the leaf, so its idle connections do not outlive it.
    override def aroundLeaf[A](body: A < (Async & Abort[Any] & Scope))(using Frame): A < (Async & Abort[Any] & Scope) =
        if Platform.isWindows then
            Sync.defer(cancel("the live suites do not run on Windows: its container daemon cannot serve the Linux mail server image"))
        else HttpClient.init().flatMap(client => HttpClient.let(client)(body))

    /** Runs `leaf` under a slot of accounts no other leaf uses, on the shared server of `kind`. A leaf that fails, is interrupted or times
      * out prints the tail of the server's log: a message the server bounced or refused shows in the leaf only as a wait that never ends.
      */
    protected def server[A](kind: EmailLiveServer.Kind = EmailLiveServer.Kind.Standard)(
        leaf: EmailLiveServer => A < (Async & Abort[Throwable] & Scope)
    )(using Frame): A < (Async & Abort[Throwable] & Scope) =
        observed(EmailLiveServer.shared(kind))(leaf)

    /** Runs `leaf` on a server of its own, for a leaf that has the server refuse a credential. Dovecot delays each further failure from the
      * same address longer than the last until a login succeeds, and Postfix answers the fourth with a 454, measured on this image. Every
      * leaf connects from one address, so a refusal on the shared server would delay or fail the logins of the leaves after it.
      */
    protected def ownServer[A](kind: EmailLiveServer.Kind = EmailLiveServer.Kind.Standard)(
        leaf: EmailLiveServer => A < (Async & Abort[Throwable] & Scope)
    )(using Frame): A < (Async & Abort[Throwable] & Scope) =
        observed(EmailLiveServer.init(kind))(leaf)

    private def observed[A](
        start: EmailLiveServer < (Async & Scope & Abort[ContainerException | FileSystemException])
    )(leaf: EmailLiveServer => A < (Async & Abort[Throwable] & Scope))(using Frame): A < (Async & Abort[Throwable] & Scope) =
        start.map { mail =>
            Scope.ensure {
                case Present(error) =>
                    mail.postMortem.map(log => Console.printLineErr(s"$name: the leaf ended with $error; server log:\n$log"))
                case Absent => Kyo.unit
            }.andThen(leaf(mail))
        }

end EmailLiveSuite
