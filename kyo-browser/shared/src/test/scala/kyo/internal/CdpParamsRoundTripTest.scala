package kyo.internal

import kyo.*
import kyo.internal.CdpTypes.*
import kyo.internal.cdp.PageDownload
import kyo.internal.cdp.PageDownload.SetDownloadBehaviorParams
import scala.compiletime.testing.typeChecks

/** Pure JSON round-trip tests for [[CdpParams]] case classes, [[CdpTypes]] opaque types, and [[NodeRef]].
  *
  * No browser, no I/O; every scenario completes in well under a second.
  *
  * Coverage:
  *   - each `CdpParams` case class round-trips encode→decode (driver-style: one assertion per class so a single broken codec does not mask
  *     the rest)
  *   - optional fields (`Maybe = Absent`/`Present(_)`) round-trip
  *   - `EvalParams` defaults (`returnByValue=true`, `awaitPromise=false`) appear in the encoded JSON
  *   - `BoxModel` and `BoxModelContent` decoders reject malformed input (typed `Result.Failure`)
  *   - `TargetId`/`SessionId`/`NodeId` opaque round-trip + compile-time `CanEqual` cross-type rejection
  *   - `NodeRef.apply` + `backendNodeId` round-trip via `derives Schema`
  *
  * ===Round-trip equality strategy===
  * `kyo.Test` extends `NonImplicitAssertions`, so `==` between case-class instances requires an explicit `CanEqual`. The CdpParams case
  * classes are private to the `kyo` package and do not declare `CanEqual` instances. Rather than synthesise dozens of `given CanEqual`
  * instances purely for tests, the round-trip helper compares the re-encoded JSON of the decoded value against the input JSON. This is a
  * sound round-trip check: a stable codec must satisfy `encode(decode(encode(v))) == encode(v)` for any `v`.
  */
class CdpParamsRoundTripTest extends kyo.BaseBrowserTest:

    /** Round-trip helper: encode → decode → re-encode → compare encoded strings.
      *
      * Returns `Absent` on success and `Present(reason)` on failure, so the driver-style scenario collects every failing class in one pass
      * without aborting on the first.
      */
    private def roundTrip[A](value: A)(using Schema[A], Frame): Maybe[String] =
        val encoded = Json.encode[A](value)
        Json.decode[A](encoded) match
            case Result.Success(decoded) =>
                val reEncoded = Json.encode[A](decoded)
                if reEncoded == encoded then Absent
                else Present(s"re-encoded mismatch:\n  original=$encoded\n  reEncoded=$reEncoded")
            case Result.Failure(err) => Present(s"decode failed: encoded=$encoded error=$err")
            case Result.Panic(ex)    => Present(s"decode panicked: encoded=$encoded ex=${ex.getMessage}")
        end match
    end roundTrip

    "CdpParams round-trip" - {

        // --- Driver-style enumeration of every CdpParams case class. ---
        // One entry per class; failures are collected and reported together so a single broken codec
        // does not mask the rest. The driver Seq enumerates all case classes derived `Json` in
        // `CdpParams.scala` plus `CookieWire` (the embedded wire value carried by NetworkGetCookiesResult; the public
        // `Browser.Cookie` is derived from `CookieWire` at the CDP boundary and is not directly serialised).
        "each CdpParams case class round-trips encode → decode (driver-style)" in {
            val cases: Seq[(String, Maybe[String])] = Seq(
                "CreateTargetParams"          -> roundTrip(CreateTargetParams("https://example.com", Present("ctx-1"))),
                "CreateTargetResult"          -> roundTrip(CreateTargetResult("target-1")),
                "AttachParams"                -> roundTrip(AttachParams("target-1", flatten = true)),
                "AttachResult"                -> roundTrip(AttachResult("session-1")),
                "CloseTargetParams"           -> roundTrip(CloseTargetParams("target-1")),
                "CreateBrowserContextResult"  -> roundTrip(CreateBrowserContextResult("ctx-1")),
                "DisposeBrowserContextParams" -> roundTrip(DisposeBrowserContextParams("ctx-1")),
                "NavigateParams"              -> roundTrip(NavigateParams("https://example.com")),
                "NavigateResult"              -> roundTrip(NavigateResult("frame-1", Present("loader-1"))),
                "NavigationEntry"             -> roundTrip(NavigationEntry(1, "https://a", "A")),
                "NavigationHistory"           -> roundTrip(NavigationHistory(0, Seq(NavigationEntry(1, "https://a", "A")))),
                "NavigateToEntryParams"       -> roundTrip(NavigateToEntryParams(2)),
                "ReloadParams"                -> roundTrip(ReloadParams(ignoreCache = true)),
                "EvalParams"                  -> roundTrip(EvalParams("1+1")),
                "EvalResult"                  -> roundTrip(EvalResult(RemoteObject.`string`("hi"), Absent)),
                "RemoteObject.string"         -> roundTrip[RemoteObject](RemoteObject.`string`("hi", Present("hi"))),
                "RemoteObject.number"         -> roundTrip[RemoteObject](RemoteObject.`number`(42.0, Present("42"))),
                "RemoteObject.boolean"        -> roundTrip[RemoteObject](RemoteObject.`boolean`(true)),
                "RemoteObject.object"         -> roundTrip[RemoteObject](RemoteObject.`object`(description = Present("Promise"))),
                "RemoteObject.null"           -> roundTrip[RemoteObject](RemoteObject.`object`(subtype = Present("null"))),
                "RemoteObject.undefined"      -> roundTrip[RemoteObject](RemoteObject.`undefined`()),
                "ExceptionDetails" -> roundTrip(ExceptionDetails(
                    Present("boom"),
                    Present(RemoteObject.`object`(description = Present("Error")))
                )),
                "ViewportParams" -> roundTrip(ViewportParams(800, 600, deviceScaleFactor = 2, mobile = true)),
                "ScreenshotClip" -> roundTrip(ScreenshotClip(0.0, 0.0, 100.0, 200.0, scale = 2.0)),
                "ScreenshotParams" -> roundTrip(ScreenshotParams(
                    Browser.ScreenshotFormat.Png,
                    Present(80),
                    Present(ScreenshotClip(0, 0, 10, 10))
                )),
                "ScreenshotResult" -> roundTrip(ScreenshotResult("aGVsbG8=")),
                "PrintToPdfResult" -> roundTrip(PrintToPdfResult("cGRm")),
                "DispatchKeyEventParams" -> roundTrip(DispatchKeyEventParams(
                    KeyEventType.Down,
                    Present("a"),
                    Present("a"),
                    Present("KeyA"),
                    Present(65),
                    Present(0)
                )),
                "GetDocumentParams"       -> roundTrip(GetDocumentParams(Present(2))),
                "GetBoxModelParams"       -> roundTrip(GetBoxModelParams(backendNodeId = 7)),
                "BoxModel"                -> roundTrip(BoxModel(BoxModelContent(Seq(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)))),
                "BoxModelContent"         -> roundTrip(BoxModelContent(Seq(1.0, 2.0, 3.0, 4.0))),
                "MouseEventParams"        -> roundTrip(MouseEventParams(MouseEventType.Pressed, 100, 200, Present("left"), Present(1))),
                "NetworkGetCookiesParams" -> roundTrip(NetworkGetCookiesParams(Present(Seq("https://example.com")))),
                "NetworkGetCookiesResult" -> roundTrip(
                    NetworkGetCookiesResult(Seq(CookieWire(
                        "k",
                        "v",
                        Present("d"),
                        Present("/"),
                        Present(1.0),
                        Present(2),
                        Present(true),
                        Present(false),
                        Present("Lax")
                    )))
                ),
                "CookieWire" -> roundTrip(CookieWire(
                    "k",
                    "v",
                    Present("d"),
                    Present("/"),
                    Present(1.0),
                    Present(2),
                    Present(true),
                    Present(false),
                    Present("Lax")
                )),
                "NetworkSetCookieParams" -> roundTrip(
                    NetworkSetCookieParams(
                        "k",
                        "v",
                        Present("https://example.com"),
                        Present("d"),
                        Present("/"),
                        Present(1.0),
                        Present(true),
                        Present(false),
                        Present("Lax")
                    )
                ),
                "NetworkDeleteCookiesParams" -> roundTrip(NetworkDeleteCookiesParams("k", Present("https://example.com"), Present("d"))),
                "DescribeNodeParams"         -> roundTrip(DescribeNodeParams(7)),
                "DescribeNodeResult"         -> roundTrip(DescribeNodeResult(DescribedNode(99))),
                "DescribedNode"              -> roundTrip(DescribedNode(99)),
                "SetFileInputFilesParams"    -> roundTrip(SetFileInputFilesParams(Seq("/tmp/a.txt", "/tmp/b.txt"), 7)),
                "TargetInfo"                 -> roundTrip(TargetInfo("target-1", "page", "https://example.com")),
                "GetTargetsResult"           -> roundTrip(GetTargetsResult(Seq(TargetInfo("target-1", "page", "https://example.com")))),
                "SetDownloadBehaviorParams" -> roundTrip(SetDownloadBehaviorParams(
                    PageDownload.Behavior.Allow,
                    Present("/tmp"),
                    Present(true)
                )),
                "HandleJavaScriptDialogParams" -> roundTrip(HandleJavaScriptDialogParams(accept = true, promptText = "ok"))
            )
            val failures = cases.collect { case (name, Present(reason)) => s"$name → $reason" }
            assert(failures.isEmpty, s"round-trip failures (${failures.size}/${cases.size}):\n${failures.mkString("\n")}")
        }

        // --- Optional field: Absent omits the JSON key, Present writes it; both round-trip. ---
        // Uses CreateTargetParams.browserContextId as the fixture (a `Maybe[String]` field with default Absent).
        "Maybe = Absent omits the JSON field; Maybe = Present(v) writes it; both round-trip" in {
            val absent  = CreateTargetParams("https://example.com", browserContextId = Absent)
            val present = CreateTargetParams("https://example.com", browserContextId = Present("ctx-99"))

            val absentJson  = Json.encode[CreateTargetParams](absent)
            val presentJson = Json.encode[CreateTargetParams](present)

            assert(!absentJson.contains("browserContextId"), s"expected `browserContextId` field absent for Absent, got: $absentJson")
            assert(presentJson.contains("\"browserContextId\""), s"expected `browserContextId` key present for Present, got: $presentJson")
            assert(presentJson.contains("ctx-99"), s"expected `ctx-99` value present for Present, got: $presentJson")

            // Round-trip via re-encode equality (see class scaladoc for rationale).
            Json.decode[CreateTargetParams](absentJson) match
                case Result.Success(v) =>
                    val re = Json.encode[CreateTargetParams](v)
                    assert(re == absentJson, s"Absent round-trip mismatch: original=$absentJson reEncoded=$re")
                case other => fail(s"Absent decode failed: $other")
            end match

            Json.decode[CreateTargetParams](presentJson) match
                case Result.Success(v) =>
                    val re = Json.encode[CreateTargetParams](v)
                    assert(re == presentJson, s"Present round-trip mismatch: original=$presentJson reEncoded=$re")
                case other => fail(s"Present decode failed: $other")
            end match
        }

        // --- EvalParams defaults appear in the encoded JSON. ---
        // The defaults `returnByValue=true` and `awaitPromise=false` must materialise in the wire payload, not
        // be silently elided as zio-schema defaults; the CDP server expects both keys.
        "EvalParams() defaults: returnByValue=true and awaitPromise=false appear in encoded JSON" in {
            val params  = EvalParams("1+1")
            val encoded = Json.encode[EvalParams](params)
            assert(
                encoded.contains("\"returnByValue\":true"),
                s"expected `\"returnByValue\":true` in encoded JSON, got: $encoded"
            )
            assert(
                encoded.contains("\"awaitPromise\":false"),
                s"expected `\"awaitPromise\":false` in encoded JSON, got: $encoded"
            )
            assert(encoded.contains("\"expression\":\"1+1\""), s"expected expression key, got: $encoded")
            // And the defaults round-trip: decoding the encoded form preserves the exact field values.
            Json.decode[EvalParams](encoded) match
                case Result.Success(v) =>
                    assert(v.expression == "1+1")
                    assert(v.returnByValue == true)
                    assert(v.awaitPromise == false)
                    val re = Json.encode[EvalParams](v)
                    assert(re == encoded, s"defaults round-trip mismatch: original=$encoded reEncoded=$re")
                case other => fail(s"defaults decode failed: $other")
            end match
        }
    }

    "decoder failure modes" - {

        // --- BoxModel rejects malformed input as typed Result.Failure. ---
        // The CDP shape is `{"model":{"content":[...]}}`. Feeding a top-level shape that does not match
        // must produce Result.Failure (not Success, not Panic).
        "BoxModel decoder rejects malformed input as Result.Failure" in {
            val malformed = """{"definitelyNot":"a box model"}"""
            Json.decode[BoxModel](malformed) match
                case Result.Failure(err) =>
                    assert(Maybe(err.getMessage).exists(_.nonEmpty), s"expected non-empty failure message, got: $err")
                case Result.Success(v) => fail(s"expected Result.Failure but got Success($v)")
                case Result.Panic(ex)  => fail(s"expected Result.Failure but got Panic(${ex.getMessage})")
            end match
        }

        // --- BoxModelContent rejects malformed input as typed Result.Failure. ---
        "BoxModelContent decoder rejects malformed input as Result.Failure" in {
            val malformed = """{"content":"not-an-array"}"""
            Json.decode[BoxModelContent](malformed) match
                case Result.Failure(err) =>
                    assert(Maybe(err.getMessage).exists(_.nonEmpty), s"expected non-empty failure message, got: $err")
                case Result.Success(v) => fail(s"expected Result.Failure but got Success($v)")
                case Result.Panic(ex)  => fail(s"expected Result.Failure but got Panic(${ex.getMessage})")
            end match
        }
    }

    "RemoteObject discriminated decode preserves description across extra wire fields" - {

        "Promise-shaped wire decodes to RemoteObject.object with description even when CDP sends sibling fields" in {
            // CDP returns a Promise as: {type:"object", subtype:"promise", className:"Promise", description:"Promise", objectId:"..."}.
            // Permissive decoding must skip subtype/className/objectId and pick the `object` variant with description Present.
            val wire =
                """{"type":"object","subtype":"promise","className":"Promise","description":"Promise","objectId":"abc-1"}"""
            Json.decode[RemoteObject](wire) match
                case Result.Success(ro: RemoteObject.`object`) =>
                    assert(ro.description == Present("Promise"), s"description=${ro.description}")
                case other => fail(s"expected RemoteObject.object but got: $other")
            end match
        }

        "description-first wire still decodes correctly when discriminator comes after" in {
            val wire = """{"description":"Promise","type":"object"}"""
            Json.decode[RemoteObject](wire) match
                case Result.Success(ro: RemoteObject.`object`) =>
                    assert(ro.description == Present("Promise"))
                case other => fail(s"expected RemoteObject.object but got: $other")
            end match
        }

        "object wire with value:{} (CDP for unserialisable references) decodes to RemoteObject.object" in {
            // CDP can emit `value: {}` (no description) for unserialisable references when returnByValue=true.
            // The `object` variant has no `value` field; the permissive decode must skip the embedded object.
            val wire = """{"type":"object","value":{}}"""
            Json.decode[RemoteObject](wire) match
                case Result.Success(v: RemoteObject.`object`) =>
                    assert(v.subtype == Absent && v.description == Absent)
                case other => fail(s"expected RemoteObject.object but got: $other")
            end match
        }
    }

    "opaque types round-trip and CanEqual safety" - {

        // --- TargetId / SessionId / NodeId opaque round-trip via apply / value. ---
        "TargetId.apply(s).value == s" in {
            val s   = "target-abc"
            val tid = TargetId(s)
            assert(tid.value == s)
            // Same-type CanEqual works (TargetId declares `given CanEqual[TargetId, TargetId]`).
            val tid2 = TargetId(s)
            assert(tid == tid2)
        }

        "SessionId.apply(s).value == s" in {
            val s   = "session-xyz"
            val sid = SessionId(s)
            assert(sid.value == s)
            val sid2 = SessionId(s)
            assert(sid == sid2)
        }

        "NodeId.apply(i).value == i" in {
            val i  = 42
            val n  = NodeId(i)
            val n2 = NodeId(i)
            assert(n.value == i)
            assert(n == n2)
        }

        // --- compile-time: CanEqual must reject cross-opaque-type ==. ---
        // TargetId/SessionId both erase to String at runtime, but the named CanEqual instances are
        // strictly homogeneous, so `t == s` must fail to typecheck. typeChecks returns false when the
        // snippet would not compile.
        "TargetId == SessionId fails compile-time CanEqual check" in {
            val crossTypeCompiles = typeChecks("""
              import kyo.internal.CdpTypes.*
              val t: TargetId  = TargetId("a")
              val s: SessionId = SessionId("a")
              t == s
            """)
            assert(!crossTypeCompiles, "expected `TargetId == SessionId` to be a compile error, but it typechecked")
        }

        "TargetId == NodeId fails compile-time CanEqual check" in {
            val crossTypeCompiles = typeChecks("""
              import kyo.internal.CdpTypes.*
              val t: TargetId = TargetId("a")
              val n: NodeId   = NodeId(1)
              t == n
            """)
            assert(!crossTypeCompiles, "expected `TargetId == NodeId` to be a compile error, but it typechecked")
        }
    }

    "NodeRef" - {

        // --- NodeRef.apply(...) builds; backendNodeId returns the underlying value. ---
        "NodeRef.apply(7).backendNodeId == 7" in {
            val ref = NodeRef(7)
            assert(ref.backendNodeId == 7)
            val ref2 = NodeRef(7)
            assert(ref == ref2) // same-type CanEqual works
        }

        // --- NodeRef round-trip via derives Schema. ---
        // `NodeRef` itself is `private[kyo] opaque type` and does not derive Json. The plan calls for a
        // round-trip "via derives Schema"; exercise the round-trip through the public encoding surface
        // (DescribedNode), which carries `backendNodeId: Int` on the wire and `derives Schema`.
        "backendNodeId round-trips via DescribedNode (the JSON-bearing wrapper) preserving NodeRef equality" in {
            val ref     = NodeRef(123)
            val node    = DescribedNode(ref.backendNodeId)
            val encoded = Json.encode[DescribedNode](node)
            assert(encoded.contains("\"backendNodeId\":123"), s"expected backendNodeId=123 in $encoded")
            Json.decode[DescribedNode](encoded) match
                case Result.Success(decoded) =>
                    val recovered = NodeRef(decoded.backendNodeId)
                    assert(recovered == ref, s"NodeRef round-trip mismatch: original=$ref recovered=$recovered")
                    assert(recovered.backendNodeId == 123)
                case other => fail(s"DescribedNode decode failed: $other")
            end match
        }
    }

end CdpParamsRoundTripTest
