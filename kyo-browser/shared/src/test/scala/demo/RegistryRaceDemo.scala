package demo

import kyo.*

/** Parallel search across 5 package registries.
  *
  * Launches five fibers concurrently inside `Browser.isolate.fresh.use { Async.zip(...) }`. Each fiber gets its own child tab via the
  * isolate, so the five tabs remain independent. Each fiber navigates to its registry's search page for "json" and records the resulting
  * title/URL.
  *
  * Per-fiber errors are captured via `Abort.run[Throwable]` so that one flaky registry does not abort the whole race; the demo reports
  * `FAIL(msg)` for individual registries instead of propagating.
  */
final class RegistryRaceDemo extends BrowserDemo[Chunk[RegistryRaceDemo.Entry]]("registry-race"):

    import RegistryRaceDemo.Entry
    import RegistryRaceDemo.Registry

    private val go            = Registry("Go", "https://pkg.go.dev/search?q=json")
    private val py            = Registry("PyPI", "https://pypi.org/search/?q=json")
    private val rb            = Registry("RubyGems", "https://rubygems.org/search?query=json")
    private val packagist     = Registry("Packagist", "https://packagist.org/?query=json")
    private val hex           = Registry("Hex.pm", "https://hex.pm/packages?search=json")
    private val allRegistries = List(go, py, rb, packagist, hex)

    def flow(using Frame): Chunk[Entry] < (Browser & Async & Scope & Abort[Throwable]) =
        for
            _ <- step(1, s"Launch ${allRegistries.size} parallel searches inside isolate.fresh")
            // `Browser.isolate.fresh.use` surfaces typed `Abort[BrowserReadException]` directly through its
            // `Isolate.Keep` channel; no boundary translation is required. Per-fiber `searchOne` already converts
            // its own typed Aborts to `Entry(FAIL(...))`, so this pipeline only escalates a tab-creation failure.
            tuple <- Browser.isolate.fresh.use {
                Async.zip(searchOne(go), searchOne(py), searchOne(rb), searchOne(packagist), searchOne(hex))
            }
            entries = Chunk(tuple._1, tuple._2, tuple._3, tuple._4, tuple._5)
            _ <- step(2, "Summary")
            _ <- Kyo.foreachDiscard(entries)(e => Console.printLine("  %-10s %s".format(e.registry, e.title)))
        yield entries

    private def searchOne(r: Registry)(using
        Frame
    ): Entry < (Browser & Async & Abort[BrowserReadException]) =
        Abort.run[Throwable] {
            for
                _     <- Browser.goto(r.url)
                title <- Browser.title
                url   <- Browser.url
            yield Entry(r.name, title.trim, url)
        }.map {
            case Result.Success(entry) => entry
            case Result.Failure(ex)    => Entry(r.name, s"FAIL(${ex.getMessage})", r.url)
            case Result.Panic(ex)      => Entry(r.name, s"PANIC(${ex.getMessage})", r.url)
        }

    override def validate(result: Chunk[Entry]): Maybe[String] =
        if result.size != allRegistries.size then Present(s"expected ${allRegistries.size} entries, got ${result.size}")
        else
            val failures = result.count(e => e.title.startsWith("FAIL") || e.title.startsWith("PANIC"))
            // Allow up to 1 registry to be flaky per run (real sites go down). The demo's value is *parallel isolation*, not uptime.
            if failures > 1 then
                Present(s"$failures/${result.size} registries failed: ${result.filter(e =>
                        e.title.startsWith("FAIL") || e.title.startsWith("PANIC")
                    )}")
            else
                // Independence check: all successful tabs landed on distinct URLs.
                val successful   = result.filterNot(e => e.title.startsWith("FAIL") || e.title.startsWith("PANIC"))
                val hostsVisited = successful.flatMap(e => Maybe(java.net.URI.create(e.url).getHost)).toSet
                if hostsVisited.size < successful.size then
                    Present(s"tab-isolation broken; successful tabs don't land on distinct hosts: $hostsVisited")
                else Absent
            end if

end RegistryRaceDemo

object RegistryRaceDemo:
    case class Registry(name: String, url: String) derives CanEqual
    case class Entry(registry: String, title: String, url: String) derives CanEqual

object RegistryRaceDemoApp extends KyoApp:
    run {
        (new RegistryRaceDemo).runDemo
    }
end RegistryRaceDemoApp
