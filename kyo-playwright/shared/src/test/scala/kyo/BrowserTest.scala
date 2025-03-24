package kyo.test

import kyo.*
import kyo.Browser
import scala.util.Try

class BrowserTest extends Test:

    private val resourcePath =
        "file://" + Thread.currentThread().getContextClassLoader().getResource("basic-page.html").getPath().replaceAll(
            "basic-page.html",
            ""
        )

    "Navigation and basic properties" in run {
        Browser.run {
            for
                _      <- Browser.goto(s"$resourcePath/basic-page.html")
                status <- Browser.status
            yield
                assert(status.title == "Basic Test Page")
                assert(status.url.endsWith("basic-page.html"))
        }
    }

    "Clicking elements and checking state changes" in run {
        Browser.run {
            for
                _           <- Browser.goto(s"$resourcePath/basic-page.html")
                _           <- Browser.click("#clickButton")
                clickResult <- Browser.innerText("#clickResult")
            yield assert(clickResult.contains("Button was clicked"))
        }
    }

    "Form interaction" in run {
        Browser.run {
            for
                _      <- Browser.goto(s"$resourcePath/form-page.html")
                _      <- Browser.click("#acceptCookies")
                _      <- Browser.fill("#name", "Test User")
                _      <- Browser.fill("#email", "test@example.com")
                _      <- Browser.fill("#password", "securepassword")
                _      <- Browser.select("#category", "technology")
                _      <- Browser.check("#newsletter")
                _      <- Browser.click("button[type=submit]")
                exists <- Browser.exists("#formResult")
                result <- Browser.innerText("#formResult")
            yield
                assert(exists)
                assert(result.contains("Form Submitted"))
                assert(result.contains("test@example.com"))
        }
    }

    "Extract content using readableContent" in run {
        Browser.run {
            for
                _       <- Browser.goto(s"$resourcePath/content-page.html")
                content <- Browser.readableContent
            yield
                assert(content.contains("Browser Automation Testing"))
                assert(content.contains("Common Browser Automation Tools"))
        }
    }

    "Tab navigation and dynamic content" in run {
        Browser.run {
            for
                _           <- Browser.goto(s"$resourcePath/content-page.html")
                tab1Content <- Browser.innerText("#tab1")
                _           <- Browser.click(".tab-button[data-tab='tab2']")
                tab2Content <- Browser.innerText("#tab2")
                _           <- Browser.click(".tab-button[data-tab='tab3']")
                tab3Content <- Browser.innerText("#tab3")
            yield
                assert(tab1Content.contains("Tab 1 Content"))
                assert(tab2Content.contains("Tab 2 Content"))
                assert(tab3Content.contains("Tab 3 Content"))
        }
    }

    "Scrolling and screenshots" in run {
        Browser.run {
            for
                _                 <- Browser.goto(s"$resourcePath/infinite-scroll-page.html")
                initialScreen     <- Browser.screenshot()
                initialCount      <- Browser.count(".post")
                _                 <- Browser.scrollToNextPage()
                afterScrollScreen <- Browser.screenshot()
                newCount          <- Browser.count(".post")
                _                 <- untilTrue(Browser.count(".post").map(_ > initialCount))
            yield succeed
        }
    }

    "Element existence and attributes" in run {
        Browser.run {
            for
                _                        <- Browser.goto(s"$resourcePath/basic-page.html")
                buttonExists             <- Browser.exists("#clickButton")
                nonExistentElementExists <- Browser.exists("#nonExistentElement")
                position                 <- Browser.getElementPosition("#clickButton")
            yield
                assert(buttonExists)
                assert(!nonExistentElementExists)
                assert(position.width > 0)
                assert(position.height > 0)
        }
    }

    "JavaScript execution" in run {
        Browser.run {
            for
                _       <- Browser.goto(s"$resourcePath/basic-page.html")
                result  <- Browser.runJavaScript("return document.title;")
                _       <- Browser.runJavaScript("document.getElementById('clickResult').innerText = 'Modified by JavaScript';")
                content <- Browser.innerText("#clickResult")
            yield
                assert(result == "Basic Test Page")
                assert(content == "Modified by JavaScript")
        }
    }

    "Cookie acceptance" in run {
        Browser.run {
            for
                _                   <- Browser.goto(s"$resourcePath/form-page.html")
                bannerVisibleBefore <- Browser.exists("#cookieBanner")
                result              <- Browser.acceptCookies()
                bannerVisibleAfter  <- Browser.exists("#cookieBanner:visible")
            yield
                assert(bannerVisibleBefore)
                assert(!bannerVisibleAfter)
        }
    }

    "Toggle content visibility" in run {
        Browser.run {
            for
                _ <- Browser.goto(s"$resourcePath/basic-page.html")
                initialVisibility <-
                    Browser.runJavaScript("return window.getComputedStyle(document.getElementById('toggleContent')).display;")
                _             <- Browser.click("#toggleButton")
                newVisibility <- Browser.runJavaScript("return window.getComputedStyle(document.getElementById('toggleContent')).display;")
            yield
                assert(initialVisibility == "none")
                assert(newVisibility == "block")
        }
    }
end BrowserTest
