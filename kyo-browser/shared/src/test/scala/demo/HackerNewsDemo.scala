package demo

import kyo.*

/** Demo 1: Hacker News top-stories scraper.
  *
  * Scrapes the front page, clicks "More" to load page 2, and aggregates into a 60-row list. The challenge here is that HN's DOM has no
  * semantic structure: a story's title is in a `<tr class="athing">` and its score / comment count live on the **next sibling** `<tr>`
  * (`.subtext`). Index alignment across sibling rows is error-prone when you only have `textAll`/`attributeAll`, so this demo uses
  * `Browser.evalJson` with a JS snippet that walks the pair-of-rows structure to produce aligned records directly.
  */
final class HackerNewsDemo extends BrowserDemo[Chunk[HackerNewsDemo.Story]]("hackernews"):

    import HackerNewsDemo.Story

    // `Browser.evalJson` wraps the expression in `JSON.stringify(...)` itself; pass a plain expression, not a stringified one.
    private val extractJs =
        """[...document.querySelectorAll('tr.athing')].map((tr, i) => {
          |  const tl  = tr.querySelector('.titleline > a');
          |  const sub = tr.nextElementSibling;
          |  const score  = sub && sub.querySelector('.score');
          |  const age    = sub && sub.querySelector('.age');
          |  const links  = sub ? [...sub.querySelectorAll('a')] : [];
          |  const commentsLink = links.length ? links[links.length - 1] : null;
          |  return {
          |    rank:     i + 1,
          |    title:    tl ? tl.textContent.trim() : '',
          |    url:      tl ? tl.href : '',
          |    score:    score ? score.textContent.trim() : '',
          |    age:      age ? age.textContent.trim() : '',
          |    comments: commentsLink ? commentsLink.textContent.trim() : ''
          |  };
          |})""".stripMargin

    def flow(using Frame): Chunk[Story] < (Browser & Async & Scope & Abort[Throwable]) =
        for
            _ <- step(1, "Navigate to Hacker News front page")
            _ <- Browser.goto("https://news.ycombinator.com/")
            _ <- logState()
            _ <- snapshot()

            _     <- step(2, "Scrape top 30 stories via evalJson")
            page1 <- Browser.evalJson[Seq[Story]](extractJs).map(Chunk.from)
            _     <- log(s"page 1: scraped ${page1.size} stories")

            _ <- step(3, "Navigate to page 2 via the 'More' href")
            // The "More" anchor sits below the viewport; driving the navigation via its href directly avoids needing to scroll
            // before clicking and still exercises the "next page loads and is scrape-able" settlement path.
            _ <- Browser.eval(
                """(() => {
                    const a = document.querySelector('a.morelink');
                    if (a) { window.location.href = a.href; return 'ok'; }
                    return 'missing';
                })()"""
            )
            _ <- Browser.assertUrlSatisfies("on page 2 (?p=2)")(_.contains("p=2"))
            _ <- Browser.assertCountSatisfies(Browser.Selector.css("tr.athing"), "at least 1 story row")(_ >= 1)
            _ <- logState()
            _ <- snapshot()

            _     <- step(4, "Scrape next 30 stories")
            page2 <- Browser.evalJson[Seq[Story]](extractJs).map(Chunk.from)
            _     <- log(s"page 2: scraped ${page2.size} stories")

            all = page1 ++ page2
            _ <- step(5, s"Summary: ${all.size} stories")
            _ <- Console.printLine("%-4s %-70s %-10s %s".format("#", "title", "score", "comments"))
            _ <- Kyo.foreachDiscard(all.take(10))(s =>
                Console.printLine("%-4d %-70s %-10s %s".format(s.rank, s.title.take(68), s.score, s.comments))
            )
        yield all
    end flow

    override def validate(result: Chunk[Story]): Maybe[String] =
        if result.size < 55 then Present(s"expected ~60 stories across two pages, got ${result.size}")
        else if result.exists(_.title.isEmpty) then Present("at least one story has an empty title; selector drift?")
        else if !result.exists(_.score.nonEmpty) then Present("no story has a score; subtext row may be misaligned")
        else Absent

end HackerNewsDemo

object HackerNewsDemo:
    case class Story(rank: Int, title: String, url: String, score: String, age: String, comments: String) derives CanEqual, Schema

object HackerNewsDemoApp extends KyoApp:
    run {
        (new HackerNewsDemo).runDemo
    }
end HackerNewsDemoApp
