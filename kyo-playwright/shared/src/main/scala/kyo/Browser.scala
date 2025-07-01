package kyo

import com.microsoft.playwright.{Browser as PlaywrightBrowser, Frame as _, *}
import com.microsoft.playwright.options.*
import kyo.kernel.ArrowEffect
import scala.io.Source
import scala.jdk.CollectionConverters.*

/** Browser automation effect for web interaction.
  *
  * This effect provides a comprehensive API for browser automation using Playwright, allowing for:
  *   - Navigation (goto, back, forward, reload)
  *   - Mouse interactions (click, hover, drag and drop)
  *   - Keyboard input (type text, key presses)
  *   - Form manipulation (fill, check, select)
  *   - DOM querying and manipulation
  *   - Screenshots and content extraction
  *
  * Note: Browser execution is single-threaded, so this effect cannot be used with methods that fork computations like Async.foreach. All
  * browser operations must be executed sequentially.
  */
sealed trait Browser extends ArrowEffect[Browser.Op, Id]

object Browser:

    import Op.*

    /** Default timeout for browser operations (5 seconds). */
    val defaultTimeout = 5.seconds

    /** Runs browser operations with the default timeout.
      *
      * @param v
      *   The browser operations to run
      * @return
      *   The result of the operations
      */
    def run[A, S](v: A < (Browser & S))(using Frame): A < (Sync & S) =
        run(defaultTimeout)(v)

    /** Runs browser operations with a specified timeout.
      *
      * @param timeout
      *   The timeout duration for browser operations
      * @param v
      *   The browser operations to run
      * @return
      *   The result of the operations
      */
    def run[A, S](timeout: Duration)(v: A < (Browser & S))(using Frame): A < (Sync & S) =
        Sync {
            val playwright = Playwright.create()
            Sync.ensure(playwright.close) {
                run(playwright.chromium().launch().newPage(), timeout)(v)
            }
        }

    /** Runs browser operations with a specified page and timeout.
      *
      * @param page
      *   The Playwright page to use
      * @param timeout
      *   The timeout duration for browser operations
      * @param v
      *   The browser operations to run
      * @return
      *   The result of the operations
      */
    def run[A, S](page: Page, timeout: Duration = defaultTimeout)(v: A < (Browser & S))(using Frame): A < (Sync & S) =
        Sync {
            page.setDefaultTimeout(timeout.toMillis.toDouble)
            page.setDefaultNavigationTimeout(timeout.toMillis.toDouble)

            ArrowEffect.handle[Op, Id, Browser, A, S, Sync](Tag[Browser], v)(
                [C] =>
                    (input, cont) =>
                        for
                            _ <- Log.debug(s"Browser: Executing $input")
                            r <- Sync(cont(input.unsafeRun(page)))
                            _ <- Log.debug(s"Browser: Done $input")
                        yield r
            )
        }
    end run

    // Navigation

    private def handleResponse(response: HttpResponse)(using Frame): Unit < (Browser & Abort[Nothing]) =
        if !response.ok then
            Abort.panic(KyoException(s"Request failed: $response"))
        else
            (
        )

    /** Navigates to a specified URL.
      *
      * @param url
      *   The URL to navigate to
      * @return
      *   Unit
      */
    def goto(url: String)(using Frame): Unit < (Browser & Abort[Nothing]) =
        Goto(url).suspendWith(handleResponse)

    /** Navigates back in the browser history.
      *
      * @return
      *   Unit
      */
    def back(using Frame): Unit < (Browser & Abort[Nothing]) =
        Back.suspendWith(handleResponse)

    /** Navigates forward in the browser history.
      *
      * @return
      *   Unit
      */
    def forward(using Frame): Unit < (Browser & Abort[Nothing]) =
        Forward.suspendWith(handleResponse)

    /** Reloads the current page.
      *
      * @return
      *   Unit
      */
    def reload(using Frame): Unit < (Browser & Abort[Nothing]) =
        Reload.suspendWith(handleResponse)

    // Mouse

    /** Clicks on an element matching the selector.
      *
      * @param selector
      *   The selector to find the element
      * @param clickCount
      *   Number of clicks (default: 1)
      * @return
      *   Unit
      */
    def click(selector: String, clickCount: Int = 1)(using Frame): Unit < Browser =
        Click(selector, true, clickCount).suspendDiscard

    /** Right-clicks on an element matching the selector.
      *
      * @param selector
      *   The selector to find the element
      * @param clickCount
      *   Number of clicks (default: 1)
      * @return
      *   Unit
      */
    def clickRight(selector: String, clickCount: Int = 1)(using Frame): Unit < Browser =
        Click(selector, false, clickCount).suspendDiscard

    /** Double-clicks on an element matching the selector.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   Unit
      */
    def doubleClick(selector: String)(using Frame): Unit < Browser =
        Click(selector, true, 2).suspendDiscard

    /** Double-right-clicks on an element matching the selector.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   Unit
      */
    def doubleClickRight(selector: String)(using Frame): Unit < Browser =
        Click(selector, false, 2).suspendDiscard

    /** Clicks at specific coordinates.
      *
      * @param x
      *   The x coordinate
      * @param y
      *   The y coordinate
      * @param clickCount
      *   Number of clicks (default: 1)
      * @param left
      *   Whether to use left button (default: true)
      * @return
      *   Unit
      */
    def clickAt(x: Double, y: Double, clickCount: Int = 1, left: Boolean = true)(using Frame): Unit < Browser =
        ClickAt(x, y, left, clickCount).suspendDiscard

    /** Right-clicks at specific coordinates.
      *
      * @param x
      *   The x coordinate
      * @param y
      *   The y coordinate
      * @param clickCount
      *   Number of clicks (default: 1)
      * @return
      *   Unit
      */
    def rightClickAt(x: Double, y: Double, clickCount: Int = 1)(using Frame): Unit < Browser =
        ClickAt(x, y, false, clickCount).suspendDiscard

    /** Double-clicks at specific coordinates.
      *
      * @param x
      *   The x coordinate
      * @param y
      *   The y coordinate
      * @param left
      *   Whether to use left button (default: true)
      * @return
      *   Unit
      */
    def doubleClickAt(x: Double, y: Double, left: Boolean = true)(using Frame): Unit < Browser =
        ClickAt(x, y, left, 2).suspendDiscard

    // Keyboard

    /** Hovers over an element matching the selector.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   Unit
      */
    def hover(selector: String)(using Frame): Unit < Browser =
        Hover(selector).suspendDiscard

    /** Types text using the keyboard.
      *
      * @param text
      *   The text to type
      * @return
      *   Unit
      */
    def typeText(text: String)(using Frame): Unit < Browser =
        TypeText(text).suspendDiscard

    /** Presses a key down.
      *
      * @param key
      *   The key to press down
      * @return
      *   Unit
      */
    def keyDown(key: String)(using Frame): Unit < Browser =
        KeyDown(key).suspendDiscard

    /** Releases a key.
      *
      * @param key
      *   The key to release
      * @return
      *   Unit
      */
    def keyUp(key: String)(using Frame): Unit < Browser =
        KeyUp(key).suspendDiscard

    /** Moves the mouse to an element.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   Unit
      */
    def mouseMoveToElement(selector: String)(using Frame): Unit < Browser =
        MouseMoveToElement(selector).suspendDiscard

    /** Performs a drag and drop operation.
      *
      * @param sourceSelector
      *   The selector for the source element
      * @param targetSelector
      *   The selector for the target element
      * @return
      *   Unit
      */
    def dragAndDrop(sourceSelector: String, targetSelector: String)(using Frame): Unit < Browser =
        DragAndDrop(sourceSelector, targetSelector).suspendDiscard

    /** Focuses on an element.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   Unit
      */
    def focus(selector: String)(using Frame): Unit < Browser =
        Focus(selector).suspendDiscard

    // Dialog Handling with simplified methods

    /** Accepts a dialog (alert, confirm, prompt).
      *
      * @return
      *   Unit
      */
    def dialogAccept()(using Frame): Unit < Browser =
        DialogAccept.suspendDiscard

    /** Dismisses a dialog (alert, confirm, prompt).
      *
      * @return
      *   Unit
      */
    def dialogDismiss()(using Frame): Unit < Browser =
        DialogDismiss.suspendDiscard

    /** Fills a prompt dialog with text.
      *
      * @param text
      *   The text to enter in the prompt
      * @return
      *   Unit
      */
    def dialogFill(text: String)(using Frame): Unit < Browser =
        DialogFill(text).suspendDiscard

    // Route Handling

    /** Intercepts and reroutes requests to a specified URL.
      *
      * @param url
      *   The URL pattern to intercept
      * @param status
      *   The HTTP status code to return (default: 200)
      * @param body
      *   The response body (default: empty string)
      * @param contentType
      *   The content type (default: text/plain)
      * @return
      *   Unit
      */
    def reroute(url: String, status: Int = 200, body: String = "", contentType: String = "text/plain")(using Frame): Unit < Browser =
        Reroute(url, status, body, contentType).suspendDiscard

    /** Removes a route for a specified URL.
      *
      * @param url
      *   The URL pattern to stop intercepting
      * @return
      *   Unit
      */
    def unroute(url: String)(using Frame): Unit < Browser =
        Unroute(url).suspendDiscard

    /** Removes all routes.
      *
      * @return
      *   Unit
      */
    def unrouteAll()(using Frame): Unit < Browser =
        UnrouteAll.suspendDiscard

    // Forms

    /** Fills a form field with text.
      *
      * @param selector
      *   The selector to find the form field
      * @param text
      *   The text to fill
      * @return
      *   Unit
      */
    def fill(selector: String, text: String)(using Frame): Unit < Browser =
        Fill(selector, text).suspendDiscard

    /** Presses a key on a focused element.
      *
      * @param selector
      *   The selector to find the element
      * @param key
      *   The key to press
      * @return
      *   Unit
      */
    def press(selector: String, key: String)(using Frame): Unit < Browser =
        Press(selector, key).suspendDiscard

    /** Checks a checkbox or radio button.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   Unit
      */
    def check(selector: String)(using Frame): Unit < Browser =
        Check(selector).suspendDiscard

    /** Unchecks a checkbox.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   Unit
      */
    def uncheck(selector: String)(using Frame): Unit < Browser =
        Uncheck(selector).suspendDiscard

    /** Selects an option from a select element.
      *
      * @param selector
      *   The selector to find the select element
      * @param value
      *   The value to select
      * @return
      *   List of selected values
      */
    def select(selector: String, value: String)(using Frame): List[String] < Browser =
        Select(selector, value).suspend

    /** Selects multiple options from a select element.
      *
      * @param selector
      *   The selector to find the select element
      * @param values
      *   The values to select
      * @return
      *   List of selected values
      */
    def selectMultiple(selector: String, values: List[String])(using Frame): List[String] < Browser =
        SelectMultiple(selector, values).suspend

    // DOM

    /** Gets the inner text of an element.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   The inner text
      */
    def innerText(selector: String)(using Frame): String < Browser =
        InnerText(selector).suspend

    /** Gets the inner HTML of an element.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   The inner HTML
      */
    def innerHTML(selector: String)(using Frame): String < Browser =
        InnerHTML(selector).suspend

    /** Gets the text content of an element.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   The text content, or None if the element is not found
      */
    def textContent(selector: String)(using Frame): Maybe[String] < Browser =
        TextContent(selector).suspend

    /** Gets an attribute of an element.
      *
      * @param selector
      *   The selector to find the element
      * @param name
      *   The attribute name
      * @return
      *   The attribute value, or None if not found
      */
    def attribute(selector: String, name: String)(using Frame): Maybe[String] < Browser =
        Attribute(selector, name).suspend

    /** Finds an element by its text content.
      *
      * @param text
      *   The text to search for
      * @return
      *   The selector for the found element
      */
    def findByText(text: String)(using Frame): String < Browser =
        FindByText(text).suspend

    /** Finds an element by its ARIA role and optionally by name.
      *
      * @param role
      *   The ARIA role
      * @param name
      *   The name of the element (default: empty string)
      * @return
      *   The selector for the found element
      */
    def findByRole(role: String, name: String = "")(using Frame): String < Browser =
        FindByRole(role, name).suspend

    /** Gets the position and dimensions of an element.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   The element's position information
      */
    def getElementPosition(selector: String)(using Frame): ElementPosition < Browser =
        GetElementPosition(selector).suspend

    /** Checks if an element exists.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   True if the element exists, false otherwise
      */
    def exists(selector: String)(using Frame): Boolean < Browser =
        Exists(selector).suspend

    /** Counts the number of elements matching a selector.
      *
      * @param selector
      *   The selector to find elements
      * @return
      *   The number of matching elements
      */
    def count(selector: String)(using Frame): Int < Browser =
        Count(selector).suspend

    // Scroll

    /** Scrolls to an element.
      *
      * @param selector
      *   The selector to find the element
      * @return
      *   Unit
      */
    def scrollToElement(selector: String)(using Frame): Unit < Browser =
        ScrollToElement(selector).suspendDiscard

    /** Scrolls to the bottom of the page.
      *
      * @return
      *   Unit
      */
    def scrollToBottom()(using Frame): Unit < Browser =
        ScrollToBottom.suspendDiscard

    /** Scrolls to the top of the page.
      *
      * @return
      *   Unit
      */
    def scrollToTop()(using Frame): Unit < Browser =
        ScrollToTop.suspendDiscard

    /** Scrolls down by one viewport height with optional overlap.
      *
      * @param overlap
      *   The fraction of the viewport to overlap (default: 0.2)
      * @return
      *   Unit
      */
    def scrollToNextPage(overlap: Double = 0.2)(using Frame): Unit < Browser =
        ScrollToNextPage(overlap).suspendDiscard

    /** Scrolls up by one viewport height with optional overlap.
      *
      * @param overlap
      *   The fraction of the viewport to overlap (default: 0.2)
      * @return
      *   Unit
      */
    def scrollToPreviousPage(overlap: Double = 0.2)(using Frame): Unit < Browser =
        ScrollToPreviousPage(overlap).suspendDiscard

    // Content

    /** Information about the current page status. */
    case class Status(url: String, title: String)

    /** Gets the current page status (URL and title).
      *
      * @return
      *   The page status
      */
    def status(using Frame): Status < Browser =
        GetStatus.suspend

    /** Extracts the main content of the page in a readable format.
      *
      * Uses Readability.js to extract the main content and converts it to Markdown.
      *
      * @return
      *   The readable content as Markdown
      */
    def readableContent(using Frame): String < Browser =
        ReadableContent.suspend

    /** Takes a screenshot of the page.
      *
      * @param width
      *   The viewport width (default: 1280)
      * @param height
      *   The viewport height (default: 720)
      * @param quality
      *   The JPEG quality (default: 90)
      * @return
      *   The screenshot as an Image
      */
    def screenshot(width: Int = 1280, height: Int = 720, quality: Int = 90)(using Frame): Image < Browser =
        Screenshot(width, height, quality).suspend

    // Utils

    /** Waits for network activity to settle.
      *
      * @param millis
      *   The timeout in milliseconds
      * @return
      *   Unit
      */
    def waitForNetworkIdle(millis: Long)(using Frame): Unit < Browser =
        WaitForNetworkIdle(millis).suspendDiscard

    /** Attempts to accept cookies by clicking common cookie consent buttons.
      *
      * @return
      *   True if cookies were accepted, false otherwise
      */
    def acceptCookies()(using Frame): Boolean < Browser =
        AcceptCookies.suspendWith(_ == "OK")

    /** Executes JavaScript code in the browser.
      *
      * @param js
      *   The JavaScript code to execute
      * @return
      *   The result of the execution as a string
      */
    def runJavaScript(js: String)(using Frame): String < Browser =
        RunJavaScript(js).suspend

    /** Sets the timeout for browser operations.
      *
      * @param duration
      *   The timeout duration
      * @return
      *   Unit
      */
    def setTimeout(duration: Duration)(using Frame): Unit < Browser =
        SetTimeout(duration).suspendDiscard

    /** Information about an element's position and dimensions. */
    case class ElementPosition(x: Double, y: Double, width: Double, height: Double, centerX: Double, centerY: Double)

    /** Base class for browser operations. */
    sealed abstract class Op[+A](private[Browser] val unsafeRun: Page => A):
        def suspend(using Frame): A < Browser                                = ArrowEffect.suspend(Tag[Browser], this)
        def suspendDiscard(using Frame): Unit < Browser                      = suspendWith(_ => ())
        def suspendWith[B, S](f: A => B < S)(using Frame): B < (Browser & S) = ArrowEffect.suspendWith(Tag[Browser], this)(f)
    end Op

    object Op:

        case class HttpResponse(ok: Boolean, code: Int, text: String)

        extension (self: Response)
            def toHttpResponse = HttpResponse(self.ok(), self.status(), self.statusText())

        private def escapeString(s: String): String =
            s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")

        // Navigation

        case class Goto(url: String)
            extends Op(_.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.LOAD)).toHttpResponse)

        case object Back
            extends Op(_.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.LOAD)).toHttpResponse)

        case object Forward
            extends Op(_.goForward(new Page.GoForwardOptions().setWaitUntil(WaitUntilState.LOAD)).toHttpResponse)

        case object Reload
            extends Op(_.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.LOAD)).toHttpResponse)

        // Mouse

        case class Click(selector: String, left: Boolean, clickCount: Int)
            extends Op(
                _.click(
                    selector,
                    new Page.ClickOptions().setButton(if left then MouseButton.LEFT else MouseButton.RIGHT).setClickCount(clickCount)
                )
            )

        case class ClickAt(x: Double, y: Double, left: Boolean, clickCount: Int)
            extends Op(page =>
                page.mouse().move(x, y)
                page.mouse().click(
                    x,
                    y,
                    new Mouse.ClickOptions()
                        .setButton(if left then MouseButton.LEFT else MouseButton.RIGHT)
                        .setClickCount(clickCount)
                )
            )

        case class Hover(selector: String)
            extends Op(_.hover(selector))

        // Keyboard

        case class TypeText(text: String)
            extends Op(_.keyboard().`type`(text))

        case class KeyDown(key: String)
            extends Op(_.keyboard().down(key))

        case class KeyUp(key: String)
            extends Op(_.keyboard().up(key))

        case class MouseMoveToElement(selector: String)
            extends Op(page =>
                val bounds = page.locator(selector).boundingBox()
                page.mouse().move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2)
            )

        case class DragAndDrop(sourceSelector: String, targetSelector: String)
            extends Op(page =>
                val source = page.locator(sourceSelector)
                val target = page.locator(targetSelector)
                source.dragTo(target)
            )

        case class Focus(selector: String)
            extends Op(_.focus(selector))

        // Dialog Handling

        case object DialogAccept
            extends Op(_.onDialog(dialog => dialog.accept()))

        case object DialogDismiss
            extends Op(_.onDialog(dialog => dialog.dismiss()))

        case class DialogFill(text: String)
            extends Op(_.onDialog(dialog => dialog.accept(text)))

        // Route Handling

        case class Reroute(url: String, status: Int, body: String, contentType: String)
            extends Op(
                _.route(
                    url,
                    route =>
                        route.fulfill(new Route.FulfillOptions()
                            .setStatus(status)
                            .setBody(body)
                            .setContentType(contentType))
                )
            )

        case class Unroute(url: String)
            extends Op(_.unroute(url))

        case object UnrouteAll
            extends Op(_.unrouteAll())

        // Forms

        case class Fill(selector: String, text: String)
            extends Op(_.fill(selector, text))

        case class Press(selector: String, key: String)
            extends Op(_.press(selector, key))

        case class Check(selector: String)
            extends Op(_.check(selector))

        case class Uncheck(selector: String)
            extends Op(_.uncheck(selector))

        case class Select(selector: String, value: String)
            extends Op(_.selectOption(selector, value).asScala.toList)

        case class SelectMultiple(selector: String, values: List[String])
            extends Op(_.selectOption(selector, values.toArray).asScala.toList)

        // DOM

        case class InnerText(selector: String)
            extends Op(_.innerText(selector))

        case class InnerHTML(selector: String)
            extends Op(_.innerHTML(selector))

        case class TextContent(selector: String)
            extends Op(p => Maybe(p.textContent(selector)))

        case class Attribute(selector: String, name: String)
            extends Op(p => Maybe(p.getAttribute(selector, name)))

        case class FindByText(text: String)
            extends Op(_.locator(s"text='${escapeString(text)}'").first().toString())

        case class FindByRole(role: String, name: String)
            extends Op(page =>
                val nameOption = if name.isEmpty then "" else s"name='${escapeString(name)}'"
                val selector = if nameOption.isEmpty then
                    s"role=${role}"
                else
                    s"role=${role}[${nameOption}]"
                page.locator(selector).first().toString()
            )

        case class GetElementPosition(selector: String)
            extends Op(page =>
                val bounds = page.locator(selector).boundingBox()
                ElementPosition(
                    bounds.x,
                    bounds.y,
                    bounds.width,
                    bounds.height,
                    bounds.x + bounds.width / 2,
                    bounds.y + bounds.height / 2
                )
            )

        case class Exists(selector: String)
            extends Op(page =>
                try
                    page.waitForSelector(selector) != null
                catch
                    case _: PlaywrightException => false
            )

        case class Count(selector: String)
            extends Op(page => page.locator(selector).count())

        // Content

        case object GetStatus
            extends Op(page => Status(page.url(), page.title()))

        case object ReadableContent
            extends Op(page =>
                page.addScriptTag(new Page.AddScriptTagOptions()
                    .setContent(Source.fromResource("kyo/turndown.js").mkString)
                    .setType("text/javascript"))
                page.addScriptTag(new Page.AddScriptTagOptions()
                    .setContent(Source.fromResource("kyo/Readability.js").mkString)
                    .setType("text/javascript"))
                page.evaluate("""() => {
                    try {
                        const documentClone = document.cloneNode(true);
                        const reader = new Readability(documentClone);
                        const article = reader.parse();
                        
                        if (article) {
                            const turndownService = new TurndownService({
                                headingStyle: 'atx',
                                codeBlockStyle: 'fenced',
                                linkStyle: 'inlined'
                            });
                            
                            return turndownService.turndown(article.content);
                        } else {
                        return "No readable content found";
                        }
                    } catch (error) {
                        return "Error extracting readable content: " + error.message;
                    }
                    }""").toString()
            )

        // Scroll

        case class ScrollToElement(selector: String)
            extends Op(_.evaluate(s"""
                const element = document.querySelector("$selector");
                if (element) {
                    element.scrollIntoView({ block: "center" });
                }
            """))

        case object ScrollToBottom
            extends Op(_.evaluate("""
                window.scrollTo(0, document.body.scrollHeight);
            """))

        case object ScrollToTop
            extends Op(_.evaluate("""
            window.scrollTo(0, 0);
            """))

        case class ScrollToNextPage(overlap: Double)
            extends Op(_.evaluate(s"""
                const viewportHeight = window.innerHeight;
                const currentScroll = window.pageYOffset;
                const overlapPixels = Math.floor(viewportHeight * $overlap);
                window.scrollTo(0, currentScroll + viewportHeight - overlapPixels);
            """))

        case class ScrollToPreviousPage(overlap: Double)
            extends Op(_.evaluate(s"""
                const viewportHeight = window.innerHeight;
                const currentScroll = window.pageYOffset;
                const overlapPixels = Math.floor(viewportHeight * $overlap);
                window.scrollTo(0, Math.max(0, currentScroll - viewportHeight + overlapPixels));
            """))

        // Utils

        case class WaitForNetworkIdle(millis: Long)
            extends Op(_.waitForLoadState(LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(millis.toDouble)))

        case object AcceptCookies
            extends Op(page =>
                val cookieSelectors = List(
                    "button[id*='cookie' i]",
                    "button[class*='cookie' i]",
                    "button[aria-label*='cookie' i]",
                    "button[data-testid*='cookie' i]",
                    "a[id*='cookie' i]:has-text(/accept|agree|consent/i)",
                    "#consent button",
                    ".consent button",
                    "button:has-text(/accept|agree|consent|cookies/i)",
                    "[role='button']:has-text(/accept|agree|consent|cookies/i)"
                )

                var found = false
                for selector <- cookieSelectors if !found do
                    try
                        if page.locator(selector).count() > 0 then
                            page.click(selector)
                            found = true
                    catch
                        case _: Exception =>
                end for
                if found then "Ok"
                else "No supported cookie button found."
            )

        case class RunJavaScript(js: String)
            extends Op(p => "" + p.evaluate(s"() => { $js }"))

        case class Screenshot(
            width: Int,
            height: Int,
            quality: Int
        ) extends Op(page =>
                val originalViewport = page.viewportSize()
                val isFullPage       = width == Int.MaxValue && height == Int.MaxValue
                if !isFullPage then
                    page.setViewportSize(width, height)
                val options = new Page.ScreenshotOptions()
                    .setType(ScreenshotType.JPEG)
                    .setFullPage(isFullPage)
                    .setQuality(quality)
                val screenshot = page.screenshot(options)
                if !isFullPage then
                    page.setViewportSize(originalViewport.width, originalViewport.height)
                Image.fromBinary(screenshot)
            )

        case class SetTimeout(duration: Duration)
            extends Op(_.setDefaultTimeout(duration.toMillis.toDouble))
    end Op

end Browser
