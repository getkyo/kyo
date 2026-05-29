package demo

import kyo.*

/** Cookie lifecycle on a real site.
  *
  * Exercises the full cookie API surface: `cookies`, `setCookie`, `deleteCookie`, and the `withNewTab` tab model. Pins down what "fresh
  * tab" means: `withNewTab` creates a new tab **in the same browser context**, so cookies and storage are shared with the parent; reach for
  * [[kyo.Browser.isolate.fresh]] when you want the parent context isolated too.
  *
  * Target: `https://en.wikipedia.org/`, a stable real site where we can set a custom cookie, reload, verify it survives, and delete it.
  */
final class CookieDanceDemo extends BrowserDemo[CookieDanceDemo.CookieReport]("cookie-dance"):

    import CookieDanceDemo.CookieReport

    private val site       = "https://en.wikipedia.org/"
    private val cookieName = "kyo_browser_demo"

    def flow(using Frame): CookieReport < (Browser & Async & Scope & Abort[Throwable]) =
        for
            _              <- step(1, s"Load $site and list cookies already in place")
            _              <- Browser.goto(site)
            initialCookies <- Browser.cookies
            _              <- log(s"initial cookies: ${initialCookies.size} total")
            _              <- Kyo.foreachDiscard(initialCookies.take(5))(c => log(s"  - ${c.name}=${c.value.take(20)}"))

            _         <- step(2, s"Set a custom cookie $cookieName=42 on the Wikipedia domain")
            _         <- Browser.setCookie(cookieName, "42", "en.wikipedia.org")
            setResult <- Browser.cookies.map(cs => Maybe.fromOption(cs.find(_.name == cookieName)))
            _         <- log(s"set-cookie readback: $setResult")

            _           <- step(3, "Reload the page; custom cookie should persist")
            _           <- Browser.reload()
            afterReload <- Browser.cookies.map(cs => Maybe.fromOption(cs.find(_.name == cookieName)))
            _           <- log(s"after-reload: $afterReload")

            _ <- step(4, "Open a fresh tab (withNewTab); same context, so the cookie must be visible")
            freshSees <- Browser.withNewTab {
                for
                    _ <- Browser.goto(site)
                    c <- Browser.cookies.map(cs => Maybe.fromOption(cs.find(_.name == cookieName)))
                yield c
            }
            _ <- log(s"fresh tab sees cookie: $freshSees")

            _           <- step(5, "Delete the custom cookie, reload, verify it's gone")
            _           <- Browser.deleteCookie(cookieName, "en.wikipedia.org")
            _           <- Browser.reload()
            afterDelete <- Browser.cookies.map(cs => Maybe.fromOption(cs.find(_.name == cookieName)))
            _           <- log(s"after-delete: $afterDelete")
            _           <- snapshot()
        yield CookieReport(
            initialCount = initialCookies.size,
            setRoundtrip = setResult.nonEmpty,
            persistedAfterReload = afterReload.nonEmpty,
            sharedWithFreshTab = freshSees.nonEmpty,
            removedAfterDelete = afterDelete.isEmpty
        )
    end flow

    override def validate(result: CookieReport): Maybe[String] =
        if !result.setRoundtrip then Present("setCookie didn't round-trip; Browser.cookies doesn't see what we set")
        else if !result.persistedAfterReload then Present("cookie lost after Browser.reload(); persistence broken")
        else if !result.sharedWithFreshTab then
            Present(
                "withNewTab tab didn't see the parent-context cookie: isolation model bug OR withNewTab opens a separate context (which would need a doc update)"
            )
        else if !result.removedAfterDelete then Present("deleteCookie didn't remove the cookie; verify after reload was not effective")
        else Absent

end CookieDanceDemo

object CookieDanceDemo:
    case class CookieReport(
        initialCount: Int,
        setRoundtrip: Boolean,
        persistedAfterReload: Boolean,
        sharedWithFreshTab: Boolean,
        removedAfterDelete: Boolean
    ) derives CanEqual
end CookieDanceDemo

object CookieDanceDemoApp extends KyoApp:
    run {
        (new CookieDanceDemo).runDemo
    }
end CookieDanceDemoApp
