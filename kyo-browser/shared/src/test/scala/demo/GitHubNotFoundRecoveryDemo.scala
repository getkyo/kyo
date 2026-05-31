package demo

import kyo.*

/** Recovery from a 4xx navigation. Demonstrates:
  *
  *   - The strict default: `Browser.goto(url)` raises [[BrowserNavigationFailedException]] on a 4xx/5xx response. The demo catches the
  *     typed exception via `Abort.run[BrowserNavigationException]`.
  *   - Inspection after the typed failure: Chrome fully loads the page (network commit + page paint) BEFORE kyo-browser raises the
  *     status-code exception, so the 404 page content is immediately readable via `Browser.title` / `Browser.text` on the same tab.
  *   - The escape hatch: a fresh `Browser.goto(otherUrl, failOnHttpError = false)` succeeds without raising the typed exception, useful
  *     when you intentionally want to drive a flow over a 4xx page without the typed-error round-trip.
  *   - History continuity: navigate to github.com home, then `Browser.back` returns to the 404 page, `Browser.forward` returns to home.
  *
  * Target: `https://github.com/<nonexistent-org-name>`: GitHub deterministically returns 404 for non-existent org URLs, with a stable "page
  * not found" markup. (We don't rely on exact copy; title or body containing "404" / "page not found" / "couldn't find" passes, so cosmetic
  * copy changes don't break the demo.)
  */
final class GitHubNotFoundRecoveryDemo extends BrowserDemo[GitHubNotFoundRecoveryDemo.RecoveryReport]("github-404-recovery"):

    import GitHubNotFoundRecoveryDemo.RecoveryReport

    // Build the URLs at runtime so the demo doesn't bake in names that GitHub might later register.
    // Two distinct missing URLs: one for the typed-failure path, one for the lenient-mode path. They must differ so
    // the lenient goto actually changes the tab's URL (a same-URL re-goto would be a no-op against the loaded 404).
    private val missingOrgStrict  = "kyo-browser-demo-nonexistent-strict-" + "12345"
    private val missingOrgLenient = "kyo-browser-demo-nonexistent-lenient-" + "12345"
    private val missingUrlStrict  = s"https://github.com/$missingOrgStrict"
    private val missingUrlLenient = s"https://github.com/$missingOrgLenient"
    private val homeUrl           = "https://github.com/"

    def flow(using Frame): RecoveryReport < (Browser & Async & Scope & Abort[Throwable]) =
        for
            _ <- step(1, "Strict mode: goto a known-404 URL with failOnHttpError=true (the default); catch the typed exception")
            firstAttempt <- Abort.run[BrowserNavigationException] {
                Browser.goto(missingUrlStrict)
            }
            firstFailureMsg <- firstAttempt match
                case Result.Failure(ex: BrowserNavigationFailedException) =>
                    log(s"typed failure observed: ${ex.getMessage}").andThen(ex.getMessage)
                case other =>
                    fail404Mismatch(other)

            _ <- step(2, "Inspect the 404 page that's already loaded on the tab (Chrome navigated fully before kyo-browser raised)")
            // The page IS loaded: Chrome paints the 404 before the response-status check fires the typed exception.
            // No re-navigation needed; just read the title/body of the current tab.
            strictTitleText <- Browser.title
            strictBodyText  <- Browser.text(Browser.Selector.css("body"))
            _               <- log(s"strict-mode 404 title:           '$strictTitleText'")
            _               <- log(s"strict-mode 404 body (first 200): '${strictBodyText.take(200).replace("\n", " ")}'")
            _               <- snapshot()

            _ <- step(3, "Lenient mode: goto a DIFFERENT known-404 URL with failOnHttpError=false (no typed exception)")
            // Must use a different URL: the tab is already at missingUrlStrict, so a same-URL goto is a no-op
            // (navigation never commits because nothing changes).
            _ <- Browser.goto(missingUrlLenient, failOnHttpError = false)
            _ <- Browser.assertUrlSatisfies("lenient mode landed on the other missing URL")(_.contains(missingOrgLenient))

            _         <- step(4, "Navigate to github.com home (known-good URL); browser history: 404a → 404b → home")
            _         <- Browser.goto(homeUrl)
            _         <- Browser.assertTitleSatisfies("home page title mentions GitHub")(_.contains("GitHub"))
            homeTitle <- Browser.title
            _         <- snapshot()

            _         <- step(5, "Browser.back returns to the lenient 404 page")
            _         <- Browser.back
            _         <- Browser.assertUrlSatisfies("back lands on the lenient 404 URL")(_.contains(missingOrgLenient))
            backTitle <- Browser.title

            _            <- step(6, "Browser.forward returns to github.com home")
            _            <- Browser.forward
            _            <- Browser.assertUrlSatisfies("forward returns to /")(_.endsWith("github.com/"))
            forwardTitle <- Browser.title
        yield RecoveryReport(
            missingUrlStrict = missingUrlStrict,
            missingUrlLenient = missingUrlLenient,
            typedFailureMessage = firstFailureMsg,
            notFoundPageTitle = strictTitleText,
            notFoundPageBody = strictBodyText,
            homeTitle = homeTitle,
            backTitle = backTitle,
            forwardTitle = forwardTitle
        )
    end flow

    private def fail404Mismatch(other: Result[Any, Any])(using Frame): String < Abort[Throwable] =
        Abort.fail(new RuntimeException(s"expected Result.Failure(BrowserNavigationFailedException) on $missingUrlStrict but got: $other"))

    override def validate(result: RecoveryReport): Maybe[String] =
        if !result.typedFailureMessage.toLowerCase.contains("404") then
            Present(s"typed failure didn't mention 404: ${result.typedFailureMessage}")
        else if !looksLike404(result.notFoundPageTitle, result.notFoundPageBody) then
            Present(
                s"404 page title/body didn't match GitHub's 'page not found': title='${result.notFoundPageTitle}', body=${result.notFoundPageBody.take(200)}"
            )
        else if !result.homeTitle.contains("GitHub") then
            Present(s"home page title didn't contain 'GitHub': '${result.homeTitle}'")
        else if !looksLike404(result.backTitle, "") then
            Present(s"history-back didn't return us to the 404 page: title='${result.backTitle}'")
        else if !result.forwardTitle.contains("GitHub") then
            Present(s"history-forward title didn't contain 'GitHub': '${result.forwardTitle}'")
        else Absent

    private def looksLike404(title: String, body: String): Boolean =
        val haystack = (title + " " + body).toLowerCase
        haystack.contains("404") || haystack.contains("page not found") || haystack.contains("couldn't find")

end GitHubNotFoundRecoveryDemo

object GitHubNotFoundRecoveryDemo:
    case class RecoveryReport(
        missingUrlStrict: String,
        missingUrlLenient: String,
        typedFailureMessage: String,
        notFoundPageTitle: String,
        notFoundPageBody: String,
        homeTitle: String,
        backTitle: String,
        forwardTitle: String
    ) derives CanEqual
end GitHubNotFoundRecoveryDemo

object GitHubNotFoundRecoveryDemoApp extends KyoApp:
    run {
        (new GitHubNotFoundRecoveryDemo).runDemo
    }
end GitHubNotFoundRecoveryDemoApp
