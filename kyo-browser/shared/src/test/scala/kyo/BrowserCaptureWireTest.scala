package kyo

import kyo.internal.*
import kyo.internal.CdpTypes.*

/** Pure wire encode/decode tests for the new CDP type additions.
  *
  * All tests are synchronous (no Chrome, no `withBrowser`). Each test exercises the derived `Schema`
  * by encoding or decoding a concrete value and asserting on the result. No reflection is used.
  */
class BrowserCaptureWireTest extends BrowserTest:

    "ViewportParams DPR serializes as a JSON number (not integer)" in {
        val vp   = ViewportParams(390, 844, deviceScaleFactor = 3.0)
        val json = Json.encode(vp)
        val rt   = decode[ViewportParams](json)
        assert(rt.deviceScaleFactor == 3.0, s"expected 3.0, got ${rt.deviceScaleFactor}")
        assert(!json.contains("\"deviceScaleFactor\":3,"), s"unexpected integer-3 in JSON: $json")
        succeed
    }

    "ViewportParams default DPR is 1.0" in {
        val vp   = ViewportParams(800, 600)
        val json = Json.encode(vp)
        val rt   = decode[ViewportParams](json)
        assert(rt.deviceScaleFactor == 1.0, s"expected 1.0, got ${rt.deviceScaleFactor}")
        succeed
    }

    "ScreenshotParams carries captureBeyondViewport and fromSurface" in {
        val sp   = ScreenshotParams(captureBeyondViewport = Present(true), fromSurface = Present(true))
        val json = Json.encode(sp)
        assert(json.contains("\"captureBeyondViewport\":true"), s"missing captureBeyondViewport in: $json")
        assert(json.contains("\"fromSurface\":true"), s"missing fromSurface in: $json")
        val empty = Json.encode(ScreenshotParams())
        assert(!empty.contains("captureBeyondViewport"), s"unexpected captureBeyondViewport in: $empty")
        assert(!empty.contains("fromSurface"), s"unexpected fromSurface in: $empty")
        succeed
    }

    "SetEmulatedMediaParams encodes media and features in one object" in {
        val params = SetEmulatedMediaParams(
            media = Present("print"),
            features = Present(Seq(EmulatedMediaFeature("prefers-color-scheme", "dark")))
        )
        val json = Json.encode(params)
        assert(json.contains("\"media\":\"print\""), s"missing media in: $json")
        assert(json.contains("\"name\":\"prefers-color-scheme\""), s"missing feature name in: $json")
        assert(json.contains("\"value\":\"dark\""), s"missing feature value in: $json")
        val rt = decode[SetEmulatedMediaParams](json)
        assert(rt.media == Present("print"), s"expected Present(print), got ${rt.media}")
        succeed
    }

    "ScreencastFrameMetadata decodes with only the consumed fields present" in {
        val json = """{"scrollOffsetX":0,"scrollOffsetY":120,"timestamp":1700.5}"""
        val meta = decode[ScreencastFrameMetadata](json)
        assert(meta.scrollOffsetY == 120.0, s"expected 120.0, got ${meta.scrollOffsetY}")
        assert(meta.timestamp == Present(1700.5), s"expected Present(1700.5), got ${meta.timestamp}")
        assert(meta.offsetTop == Absent, s"expected Absent, got ${meta.offsetTop}")
        assert(meta.pageScaleFactor == Absent, s"expected Absent, got ${meta.pageScaleFactor}")
        assert(meta.deviceWidth == Absent, s"expected Absent, got ${meta.deviceWidth}")
        assert(meta.deviceHeight == Absent, s"expected Absent, got ${meta.deviceHeight}")
        succeed
    }

end BrowserCaptureWireTest
