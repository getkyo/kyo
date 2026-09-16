package linkcheck

// Outside the kyo package, as an application is.
import kyo.*

/** One request to a port nothing answers on, reporting how it failed, in a program that then has to exit.
  *
  * Both halves matter. The failure says which client the host selected and whether its refusal is typed: a socket host reports a connect
  * failure, and a host with no socket reports whatever its `fetch` gave. The exit says the client left nothing running behind it, which is
  * what a command-line program needs and what no test harness can show, because a harness holds the process open itself.
  */
object HttpGet extends KyoApp:
    run {
        Abort.run[Any](HttpClient.getText("http://127.0.0.1:1/")).map(result => Console.printLine(Report(result)))
    }
end HttpGet
