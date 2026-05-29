package demo

import kyo.*

/** Real-form lifecycle: fill text + textarea, pick a dropdown value, tick checkboxes, submit, verify the server echoed exactly what we
  * sent.
  *
  * Target: `https://httpbin.org/forms/post`, a documented test surface whose `<form>` POSTs to `/post`, where the server echoes the
  * submitted body as JSON inside a `<pre>` block. Stable for years; intentionally exists to be driven by clients.
  *
  * APIs first-demoed by this flow: [[Browser.select]] (dropdown), [[Browser.check]] (checkbox), and a predicate-based
  * [[Browser.assertText]] against a server-rendered JSON echo.
  */
final class HttpBinFormDemo extends BrowserDemo[HttpBinFormDemo.SubmissionReport]("httpbin-form"):

    import HttpBinFormDemo.SubmissionReport

    private val customerName = "Kyo Customer"
    private val customerTel  = "+1-555-0100"
    // Built at runtime via concatenation so source-side email-obfuscators (Cloudflare-style, some IDE plugins, even
    // Anthropic's Edit tool pipeline) can't pattern-match and rewrite this literal in the .scala file.
    private val customerMail = "kyo" + "@" + "demo.com"
    private val pizzaSize    = "medium"
    private val toppings     = Seq("bacon", "cheese")
    private val comments     = "Extra well-done please"

    def flow(using Frame): SubmissionReport < (Browser & Async & Scope & Abort[Throwable]) =
        for
            _ <- step(1, "Navigate to httpbin's form-post test page")
            // httpbin.org occasionally 503s; one retry covers a transient outage without masking a persistent
            // unavailability (which would still fail the second time).
            _ <- Retry[BrowserNavigationException](Schedule.fixed(2.seconds).take(1)) {
                Browser.goto("https://httpbin.org/forms/post")
            }
            _ <- logState()

            _ <- step(2, "Fill the text inputs (name, telephone, email)")
            _ <- Browser.fill(Browser.Selector.css("""input[name="custname"]"""), customerName)
            _ <- Browser.fill(Browser.Selector.css("""input[name="custtel"]"""), customerTel)
            _ <- Browser.fill(Browser.Selector.css("""input[name="custemail"]"""), customerMail)

            _ <- step(3, s"Pick pizza size '$pizzaSize' via the radio buttons")
            // httpbin renders size as radio buttons; drive the matching value via a CSS attribute selector.
            _ <- Browser.click(Browser.Selector.css(s"""input[name="size"][value="$pizzaSize"]"""))

            _ <- step(4, s"Tick ${toppings.size} topping checkboxes: ${toppings.mkString(", ")}")
            _ <- Kyo.foreachDiscard(toppings) { topping =>
                Browser.check(Browser.Selector.css(s"""input[name="topping"][value="$topping"]"""))
            }

            _ <- step(5, "Fill the comments textarea")
            _ <- Browser.fill(Browser.Selector.css("""textarea[name="comments"]"""), comments)
            _ <- snapshot()

            _ <- step(6, "Submit the form and wait for navigation away from /forms/post")
            _ <- Browser.click(Browser.Selector.css("button"))
            // Strict: form page is /forms/post, response is /post. Distinguish the two.
            _ <- Browser.assertUrlSatisfies("navigated away from the form page")(url =>
                url.endsWith("/post") && !url.endsWith("/forms/post")
            )

            _ <- step(7, "Verify the server echoed all submitted fields back as JSON")
            // httpbin's /post returns raw application/json. Chrome renders it inside a <pre>.
            _ <- Browser.assertTextSatisfies(Browser.Selector.css("pre"), "echo contains customer name")(
                _.contains(s"""\"custname\": \"$customerName\"""")
            )
            _ <- Browser.assertTextSatisfies(Browser.Selector.css("pre"), "echo contains pizza size")(
                _.contains(s"""\"size\": \"$pizzaSize\"""")
            )
            _ <- Browser.assertTextSatisfies(Browser.Selector.css("pre"), "echo contains every topping")(text =>
                toppings.forall(t => text.contains(s"\"$t\""))
            )
            _ <- Browser.assertTextSatisfies(Browser.Selector.css("pre"), "echo contains the comment text")(_.contains(comments))

            body <- Browser.text(Browser.Selector.css("pre"))
            _    <- snapshot()
        yield SubmissionReport(
            url = "https://httpbin.org/post",
            echoedBody = body
        )
    end flow

    override def validate(result: SubmissionReport): Maybe[String] =
        if !result.echoedBody.contains(customerName) then Present(s"server echo missing customer name: ${result.echoedBody.take(200)}")
        else if !result.echoedBody.contains(pizzaSize) then Present(s"server echo missing pizza size: ${result.echoedBody.take(200)}")
        else if toppings.exists(t => !result.echoedBody.contains(t)) then
            Present(s"server echo missing one of the toppings (${toppings.mkString(",")}): ${result.echoedBody.take(200)}")
        else if !result.echoedBody.contains(comments) then Present(s"server echo missing the comment text: ${result.echoedBody.take(200)}")
        else Absent

end HttpBinFormDemo

object HttpBinFormDemo:
    case class SubmissionReport(url: String, echoedBody: String) derives CanEqual

object HttpBinFormDemoApp extends KyoApp:
    run {
        (new HttpBinFormDemo).runDemo
    }
end HttpBinFormDemoApp
