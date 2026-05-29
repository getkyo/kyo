package demo

import kyo.*

/** Demo 5: GitHub Trending SPA navigation.
  *
  * GitHub is a React SPA. `goto` + `waitForLoad` returns when the initial HTML is parsed, but the trending list is streamed in afterwards
  * by client-side JS. This demo exercises `assertCount(sel, msg)(_ >= 5)` and the predicate-based `assertUrl(msg)(pred)` to settle on the
  * list being present before scraping. It then clicks into the first repo, verifies landing on the repo's page, goes back, and switches the
  * time-range to "Weekly" to prove the SPA re-renders.
  */
final class GitHubTrendingDemo extends BrowserDemo[GitHubTrendingDemo.TrendingReport]("github-trending"):

    import GitHubTrendingDemo.TrendingReport

    def flow(using Frame): TrendingReport < (Browser & Async & Scope & Abort[Throwable]) =
        for
            _ <- step(1, "Navigate to GitHub Trending")
            _ <- Browser.goto("https://github.com/trending")
            _ <- snapshot()

            _ <- step(2, "Wait for the trending list to stream in (SPA settlement)")
            // GitHub's markup uses `article` with varying helper classes. Wait on the generic selector; the page has no other articles,
            // so we won't over-match.
            _ <- Browser.assertCountSatisfies(Browser.Selector.css("article"), "at least 5 trending articles")(_ >= 5)
            _ <- diagnostic("article count", "document.querySelectorAll('article').length")
            _ <- diagnostic("first article h2 anchor href", "document.querySelector('article h2 a')?.getAttribute('href')")
            _ <- logState()

            _         <- step(3, "Extract top repo slugs (daily view)")
            dailyList <- Browser.attributeAll(Browser.Selector.css("article h2 a"), "href")
            _         <- log(s"daily top: ${dailyList.take(10).mkString(", ")}")

            _ <- step(4, "Navigate into the first trending repo via its href")
            // Drive the navigation via the anchor's href directly: GitHub's sticky header + `article h2 a` layout make a plain
            // click non-deterministic (the hit test can land on a child span). Settlement is the URL leaving `/trending`.
            _ <- Browser.eval(
                """(() => {
                    const a = document.querySelector('article h2 a');
                    if (a) { window.location.href = a.href; return 'ok'; }
                    return 'missing';
                })()"""
            )
            _             <- Browser.assertUrlSatisfies("no longer on /trending")(url => !url.endsWith("/trending"))
            firstRepoUrl  <- Browser.url
            firstRepoHome <- Browser.title
            _             <- log(s"repo page: $firstRepoHome at $firstRepoUrl")
            _             <- snapshot()

            _ <- step(5, "Back to trending")
            _ <- Browser.back
            _ <- Browser.assertCountSatisfies(Browser.Selector.css("article"), "at least 5 trending articles")(_ >= 5)
            _ <- logState()

            _ <- step(6, "Switch to Weekly range")
            // The trending filter sometimes lives inside a collapsed `<details>` / `.select-menu` on narrower widths. Drive the
            // navigation via the anchor's href directly so the toggle button doesn't need to be visible at this viewport.
            _ <- Browser.eval(
                """(() => {
                    const a = document.querySelector("a[href*='since=weekly']");
                    if (a) { window.location.href = a.href; return 'ok'; }
                    return 'missing';
                })()"""
            )
            _          <- Browser.assertCountSatisfies(Browser.Selector.css("article"), "at least 5 weekly trending articles")(_ >= 5)
            weeklyList <- Browser.attributeAll(Browser.Selector.css("article h2 a"), "href")
            _          <- log(s"weekly top: ${weeklyList.take(10).mkString(", ")}")
            _          <- snapshot()
        yield TrendingReport(
            dailyRepos = dailyList,
            weeklyRepos = weeklyList,
            firstRepoUrl = firstRepoUrl
        )
    end flow

    override def validate(result: TrendingReport): Maybe[String] =
        if result.dailyRepos.size < 5 then Present(s"daily trending list too small (${result.dailyRepos.size})")
        else if result.weeklyRepos.size < 5 then Present(s"weekly trending list too small (${result.weeklyRepos.size})")
        else if !result.firstRepoUrl.startsWith("https://github.com/") then
            Present(s"click-through didn't land on a GitHub repo URL: ${result.firstRepoUrl}")
        else if result.firstRepoUrl == "https://github.com/trending" then Present("click-through didn't actually navigate; URL unchanged")
        else Absent

end GitHubTrendingDemo

object GitHubTrendingDemo:
    case class TrendingReport(dailyRepos: Chunk[String], weeklyRepos: Chunk[String], firstRepoUrl: String) derives CanEqual

object GitHubTrendingDemoApp extends KyoApp:
    run {
        (new GitHubTrendingDemo).runDemo
    }
end GitHubTrendingDemoApp
