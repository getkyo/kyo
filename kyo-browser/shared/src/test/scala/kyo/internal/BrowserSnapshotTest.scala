package kyo.internal

import kyo.*

/** Pure unit tests for [[BrowserSnapshot.decodeSnapshotEnvelope]].
  *
  * The snapshot envelope is decoded from a single JS-side `JSON.stringify(...)` payload. All Schema defaults must apply (so a missing
  * `scrollX` is 0, missing storage is empty Dict) without dropping the surrounding fields. Wire-shape drift surfaces as a typed
  * [[BrowserProtocolErrorException]].
  *
  * All scenarios are pure (no browser, no I/O).
  */
class BrowserSnapshotTest extends kyo.BaseBrowserTest:

    "decodeSnapshotEnvelope round-trips a well-formed envelope, preserving every field verbatim" in {
        val wire = """{
          "url":"https://example.test/page",
          "localStorage":{"k1":"v1","k2":"v2"},
          "sessionStorage":{"s1":"sv1"},
          "formFields":[{"id":"name","type":"text","value":"alice"}],
          "scrollX":120,
          "scrollY":340,
          "focusedSelector":"#name",
          "cursorPosition":"3,3"
        }"""
        Abort.run[BrowserReadException](BrowserSnapshot.decodeSnapshotEnvelope(wire)).map {
            case Result.Success(env) =>
                assert(env.url == "https://example.test/page", s"url was '${env.url}'")
                assert(env.localStorage.get("k1") == Present("v1"), s"localStorage k1 was ${env.localStorage.get("k1")}")
                assert(env.localStorage.get("k2") == Present("v2"), s"localStorage k2 was ${env.localStorage.get("k2")}")
                assert(env.sessionStorage.get("s1") == Present("sv1"), s"sessionStorage s1 was ${env.sessionStorage.get("s1")}")
                assert(env.formFields.length == 1, s"formFields length was ${env.formFields.length}")
                assert(env.formFields(0).id == "name", s"formFields(0).id was '${env.formFields(0).id}'")
                assert(env.formFields(0).value == "alice", s"formFields(0).value was '${env.formFields(0).value}'")
                assert(env.scrollX == 120, s"scrollX was ${env.scrollX}")
                assert(env.scrollY == 340, s"scrollY was ${env.scrollY}")
                assert(env.focusedSelector == "#name", s"focusedSelector was '${env.focusedSelector}'")
                assert(env.cursorPosition == "3,3", s"cursorPosition was '${env.cursorPosition}'")
            case other => fail(s"expected Success(SnapshotEnvelope(...)) for well-formed wire but got $other")
        }
    }

    "decodeSnapshotEnvelope applies Schema defaults for an empty envelope: scrollX/Y=0, storage/formFields empty, strings empty" in {
        val wire = """{}"""
        Abort.run[BrowserReadException](BrowserSnapshot.decodeSnapshotEnvelope(wire)).map {
            case Result.Success(env) =>
                assert(env.url == "", s"url default was '${env.url}'")
                assert(env.localStorage.isEmpty, s"localStorage default was non-empty: ${env.localStorage}")
                assert(env.sessionStorage.isEmpty, s"sessionStorage default was non-empty: ${env.sessionStorage}")
                assert(env.formFields.isEmpty, s"formFields default was non-empty: ${env.formFields}")
                assert(env.scrollX == 0, s"scrollX default was ${env.scrollX}")
                assert(env.scrollY == 0, s"scrollY default was ${env.scrollY}")
                assert(env.focusedSelector == "", s"focusedSelector default was '${env.focusedSelector}'")
                assert(env.cursorPosition == "", s"cursorPosition default was '${env.cursorPosition}'")
            case other => fail(s"expected Success with defaults for empty envelope but got $other")
        }
    }

    "decodeSnapshotEnvelope applies the missing-field default for a partially-populated envelope (about:blank case)" in {
        // about:blank pages have no scrollX/Y, no storage, no form fields, but the URL is still present.
        val wire = """{"url":"about:blank"}"""
        Abort.run[BrowserReadException](BrowserSnapshot.decodeSnapshotEnvelope(wire)).map {
            case Result.Success(env) =>
                assert(env.url == "about:blank", s"url was '${env.url}'")
                assert(env.scrollX == 0, s"scrollX default was ${env.scrollX}")
                assert(env.scrollY == 0, s"scrollY default was ${env.scrollY}")
                assert(env.localStorage.isEmpty, s"localStorage default was non-empty: ${env.localStorage}")
                assert(env.formFields.isEmpty, s"formFields default was non-empty: ${env.formFields}")
            case other => fail(s"expected Success(SnapshotEnvelope(url=about:blank, ...defaults)) but got $other")
        }
    }

    "decodeSnapshotEnvelope surfaces malformed JSON as BrowserProtocolErrorException (no silent zero-snapshot)" in {
        val wire = "this is definitely not json"
        Abort.run[BrowserReadException](BrowserSnapshot.decodeSnapshotEnvelope(wire)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(ex.method == "captureSnapshot", s"expected method='captureSnapshot' but got '${ex.method}'")
                assert(
                    ex.error.contains("snapshot wire decode"),
                    s"expected 'snapshot wire decode' in error message but got '${ex.error}'"
                )
            case other => fail(s"expected Failure(BrowserProtocolErrorException) for malformed wire but got $other")
        }
    }

    "decodeSnapshotEnvelope surfaces a wire whose typed field is the wrong type as BrowserProtocolErrorException" in {
        // `scrollX` is typed Int; supplying a string for it forces a Schema-decode failure rather
        // than a silent fall-through to the field's default.
        val wire = """{"scrollX":"not-a-number"}"""
        Abort.run[BrowserReadException](BrowserSnapshot.decodeSnapshotEnvelope(wire)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(ex.method == "captureSnapshot", s"expected method='captureSnapshot' but got '${ex.method}'")
            case other => fail(s"expected Failure(BrowserProtocolErrorException) for type-mismatch but got $other")
        }
    }

end BrowserSnapshotTest
