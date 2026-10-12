package kyo

import kyo.internal.Platform

/** The live suites: leaves against real mail servers, on the real clock. The leaves share an [[EmailLiveServer]] per kind, each on accounts
  * of its own, so a leaf that disconnects sessions or recreates mailboxes cannot disturb another.
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

    /** Runs `leaf` on the shared server of `kind`, with accounts of its own. A leaf that fails, is interrupted or times out prints the
      * tail of the server's log: a message the server bounced or refused shows in the leaf only as a wait that never ends.
      */
    protected def server[A](kind: EmailLiveServer.Kind = EmailLiveServer.Kind.Standard)(
        leaf: EmailLiveServer => A < (Async & Abort[Throwable] & Scope)
    )(using Frame): A < (Async & Abort[Throwable] & Scope) =
        EmailLiveServer.init(kind).map { mail =>
            Scope.ensure {
                case Present(error) =>
                    mail.postMortem.map(log => Console.printLineErr(s"$name: the leaf ended with $error; server log:\n$log"))
                case Absent => Kyo.unit
            }.andThen(leaf(mail))
        }

end EmailLiveSuite
