package kyo.website

import kyo.*

class WebsiteExceptionTest extends Test:

    "ReadmeException carries typed detail" - {
        "detail and path fields are accessible" in {
            val ex = WebsiteReadmeException(Path("x", "README.md"), ReadmeFailure.Missing)
            assert(ex.detail == ReadmeFailure.Missing)
        }

        "message contains path and Missing" in {
            val path = Path("kyo-missing", "README.md")
            val ex   = WebsiteReadmeException(path, ReadmeFailure.Missing)
            val msg  = ex.getMessage()
            assert(msg.contains("Missing"))
            assert(msg.contains("kyo-missing"))
        }
    }

    "MarkdownException fields" - {
        "slug and detail are accessible" in {
            val ex = WebsiteMarkdownException("kyo-core", "boom")
            assert(ex.slug == "kyo-core")
            assert(ex.detail == "boom")
        }
    }

    "EmitException wraps a cause" - {
        "getCause returns the wrapped Throwable" in {
            val cause = new RuntimeException("disk full")
            val ex    = WebsiteEmitException("/v1/kyo-core/", cause)
            assert(ex.getCause() eq cause)
        }

        "route field is the route string" in {
            val cause = new RuntimeException("disk full")
            val ex    = WebsiteEmitException("/v1/kyo-core/", cause)
            assert(ex.route == "/v1/kyo-core/")
        }
    }

    "ReadmeFailure enum exhaustiveness" - {
        "all three cases match" in {
            val cases = List(ReadmeFailure.Missing, ReadmeFailure.MalformedGroups, ReadmeFailure.MalformedTable)
            val matched = cases.map {
                case ReadmeFailure.Missing         => "Missing"
                case ReadmeFailure.MalformedGroups => "MalformedGroups"
                case ReadmeFailure.MalformedTable  => "MalformedTable"
            }
            assert(matched == List("Missing", "MalformedGroups", "MalformedTable"))
        }
    }

    "leaves are WebsiteException subtypes" - {
        "WebsiteReadmeException is a WebsiteException" in {
            val ex = WebsiteReadmeException(Path("x", "README.md"), ReadmeFailure.Missing)
            assert(ex.isInstanceOf[WebsiteException])
        }

        "WebsiteMarkdownException is a WebsiteException" in {
            val ex = WebsiteMarkdownException("kyo-core", "boom")
            assert(ex.isInstanceOf[WebsiteException])
        }

        "WebsiteEmitException is a WebsiteException" in {
            val ex = WebsiteEmitException("/route/", new RuntimeException("err"))
            assert(ex.isInstanceOf[WebsiteException])
        }

        "base is KyoException" in {
            val ex = WebsiteReadmeException(Path("x", "README.md"), ReadmeFailure.Missing)
            assert(ex.isInstanceOf[KyoException])
        }
    }

end WebsiteExceptionTest
