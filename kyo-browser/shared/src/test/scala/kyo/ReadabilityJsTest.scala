package kyo

class ReadabilityJsTest extends BrowserTest:

    override def timeout = 60.seconds

    // ── role-based strip-out ────────────────────────────────────────────────
    // The ReadabilityJs script removes elements matching:
    //   [role="banner"], [role="navigation"], [role="complementary"],
    //   [role="contentinfo"], [aria-hidden="true"]
    // Each page has real content in an <article> and noise in the targeted element.

    "role=banner noise is excluded from readableContent" in run {
        withBrowser {
            onPage(
                """<body>
              |<div role="banner">BANNER_NOISE</div>
              |<article><p>MainArticleText</p></article>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticleText"), s"Expected 'MainArticleText' in: $content")
                    assert(!content.contains("BANNER_NOISE"), s"Expected 'BANNER_NOISE' to be stripped, but got: $content")
                }
            }
        }
    }

    "role=navigation noise is excluded from readableContent" in run {
        withBrowser {
            onPage(
                """<body>
              |<div role="navigation">NAV_NOISE</div>
              |<article><p>MainArticleText</p></article>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticleText"), s"Expected 'MainArticleText' in: $content")
                    assert(!content.contains("NAV_NOISE"), s"Expected 'NAV_NOISE' to be stripped, but got: $content")
                }
            }
        }
    }

    "role=complementary noise is excluded from readableContent" in run {
        withBrowser {
            onPage(
                """<body>
              |<div role="complementary">SIDEBAR_NOISE</div>
              |<article><p>MainArticleText</p></article>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticleText"), s"Expected 'MainArticleText' in: $content")
                    assert(!content.contains("SIDEBAR_NOISE"), s"Expected 'SIDEBAR_NOISE' to be stripped, but got: $content")
                }
            }
        }
    }

    "role=contentinfo noise is excluded from readableContent" in run {
        withBrowser {
            onPage(
                """<body>
              |<div role="contentinfo">FOOTER_NOISE</div>
              |<article><p>MainArticleText</p></article>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticleText"), s"Expected 'MainArticleText' in: $content")
                    assert(!content.contains("FOOTER_NOISE"), s"Expected 'FOOTER_NOISE' to be stripped, but got: $content")
                }
            }
        }
    }

    "aria-hidden=true noise is excluded from readableContent" in run {
        withBrowser {
            onPage(
                """<body>
              |<div aria-hidden="true">HIDDEN_NOISE</div>
              |<article><p>MainArticleText</p></article>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticleText"), s"Expected 'MainArticleText' in: $content")
                    assert(!content.contains("HIDDEN_NOISE"), s"Expected 'HIDDEN_NOISE' to be stripped, but got: $content")
                }
            }
        }
    }

    // ── class-based strip-out ───────────────────────────────────────────────
    // The script strips: .ad, .ads, .advertisement, .social-share, .sidebar,
    // .menu, .popup, .modal, .overlay, .cookie-banner
    // Plan cites: .ad, .ads, .advertisement

    "class .ad noise is excluded from readableContent" in run {
        withBrowser {
            onPage(
                """<body>
              |<div class="ad">AD_NOISE</div>
              |<article><p>MainArticleText</p></article>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticleText"), s"Expected 'MainArticleText' in: $content")
                    assert(!content.contains("AD_NOISE"), s"Expected 'AD_NOISE' (.ad) to be stripped, but got: $content")
                }
            }
        }
    }

    "class .ads noise is excluded from readableContent" in run {
        withBrowser {
            onPage(
                """<body>
              |<div class="ads">ADS_NOISE</div>
              |<article><p>MainArticleText</p></article>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticleText"), s"Expected 'MainArticleText' in: $content")
                    assert(!content.contains("ADS_NOISE"), s"Expected 'ADS_NOISE' (.ads) to be stripped, but got: $content")
                }
            }
        }
    }

    "class .advertisement noise is excluded from readableContent" in run {
        withBrowser {
            onPage(
                """<body>
              |<div class="advertisement">ADVERTISEMENT_NOISE</div>
              |<article><p>MainArticleText</p></article>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("MainArticleText"), s"Expected 'MainArticleText' in: $content")
                    assert(
                        !content.contains("ADVERTISEMENT_NOISE"),
                        s"Expected 'ADVERTISEMENT_NOISE' (.advertisement) to be stripped, but got: $content"
                    )
                }
            }
        }
    }

    // ── body-fallback ───────────────────────────────────────────────────────
    // When there is no <article>, <main>, or [role="main"], readableContent
    // should fall back to document.body.innerText.

    "readableContent falls back to body when no article/main/role=main present" in run {
        withBrowser {
            onPage(
                """<body>
              |<div><p>BodyFallbackText</p></div>
              |<div><p>MoreBodyContent</p></div>
              |</body>""".stripMargin
            ) {
                Browser.readableContent.map { content =>
                    assert(content.contains("BodyFallbackText"), s"Expected 'BodyFallbackText' (body fallback) in: $content")
                    assert(content.contains("MoreBodyContent"), s"Expected 'MoreBodyContent' (body fallback) in: $content")
                }
            }
        }
    }

end ReadabilityJsTest
