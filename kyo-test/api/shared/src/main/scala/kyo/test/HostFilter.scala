package kyo.test

import kyo.Chunk
import kyo.Maybe
import kyo.internal.Platform

/** A requirement on the host a leaf or group runs on, added by `.onlyBrowser` and `.notBrowser`.
  *
  * A platform filter (`.onlyJs`, `.notWasm`, ...) is settled before the tests run, because the platform is fixed when the tests are compiled
  * or linked. The host is not: one Scala.js link runs on Node and in a browser alike, so a host filter is checked when the suite registers
  * its tests. A leaf whose filters do not all hold is reported `Cancelled`, with a reason naming the requirement and the host, and its body
  * never runs. A filtered group is reported as one cancelled entry in place of its leaves, as `.only(false)` reports one skipped entry.
  *
  * On the JVM and Scala Native the host is never a browser, so `.onlyBrowser` cancels there and `.notBrowser` always holds.
  *
  * Filters accumulate: `"x".onlyBrowser.notBrowser` holds nowhere, and a filter chained after a platform filter keeps the platform filter.
  *
  * @see
  *   [[TestBuilder.hostFilters]] where a chain of decorators records them
  */
enum HostFilter derives CanEqual:

    /** Holds in a browser page or a browser worker. */
    case OnlyBrowser

    /** Holds on every host but a browser: the JVM, Scala Native, Node, Bun, Deno, or another JavaScript host. */
    case NotBrowser

end HostFilter

object HostFilter:

    /** The reason a test with `filters` is cancelled on the current host, or `Absent` when every filter holds. */
    private[test] def unmet(filters: Chunk[HostFilter]): Maybe[String] =
        if filters.isEmpty then Maybe.empty else unmet(filters, Platform.host)

    /** The reason a test with `filters` is cancelled on `host`, from the first filter that does not hold, or `Absent` when every filter holds. */
    private[test] def unmet(filters: Chunk[HostFilter], host: Platform.Host): Maybe[String] =
        val browser = (host eq Platform.Host.BrowserMain) || (host eq Platform.Host.BrowserWorker)
        Maybe.fromOption(filters.collectFirst {
            case OnlyBrowser if !browser => s"runs only in a browser; this host is ${describe(host)}"
            case NotBrowser if browser   => s"does not run in a browser; this host is ${describe(host)}"
        })
    end unmet

    private def describe(host: Platform.Host): String =
        host match
            case Platform.Host.Jvm           => "the JVM"
            case Platform.Host.Native        => "Scala Native"
            case Platform.Host.Node          => "Node"
            case Platform.Host.Bun           => "Bun"
            case Platform.Host.Deno          => "Deno"
            case Platform.Host.BrowserMain   => "a browser page"
            case Platform.Host.BrowserWorker => "a browser worker"
            case Platform.Host.OtherJs       => "a JavaScript host that is neither Node, Bun, Deno, nor a browser"

end HostFilter
