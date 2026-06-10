package kyo.internal

import kyo.*

/** Pure unit tests for [[StabilitySampler.parseOutcome]].
  *
  * The in-page sampler returns a JSON envelope `{"tag":"stable"|"unstable","value":...}`. The decode path is the gate between the in-page
  * wire and every stability-aware assertion: a silent decode failure here would mask a flicker as stable and break every probe.
  *
  * All scenarios are pure (no browser, no I/O). Synthetic wire strings drive `parseOutcome` directly.
  */
class StabilitySamplerTest extends kyo.BaseBrowserTest:

    "parseOutcome decodes a well-formed stable envelope to Outcome.Stable" in {
        val wire = """{"tag":"stable","value":"42"}"""
        Abort.run[BrowserReadException](StabilitySampler.parseOutcome(wire)).map {
            case Result.Success(StabilitySampler.Outcome.Stable(v)) =>
                assert(v == "42", s"expected stable value '42' but got '$v'")
            case other => fail(s"expected Success(Outcome.Stable(\"42\")) but got $other")
        }
    }

    "parseOutcome decodes a well-formed unstable envelope to Outcome.Unstable, preserving the divergent value" in {
        val wire = """{"tag":"unstable","value":"newval"}"""
        Abort.run[BrowserReadException](StabilitySampler.parseOutcome(wire)).map {
            case Result.Success(StabilitySampler.Outcome.Unstable(v)) =>
                assert(v == "newval", s"expected unstable value 'newval' but got '$v'")
            case other => fail(s"expected Success(Outcome.Unstable(\"newval\")) but got $other")
        }
    }

    "parseOutcome surfaces unknown tag as BrowserProtocolErrorException (typed protocol error, never silent stable)" in {
        // A tag the in-page IIFE does not emit must not be silently mis-read as stable; it indicates JS-template drift.
        val wire = """{"tag":"weird","value":"x"}"""
        Abort.run[BrowserReadException](StabilitySampler.parseOutcome(wire)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(
                    ex.method == "StabilitySampler.sampleWindow",
                    s"expected method='StabilitySampler.sampleWindow' but got '${ex.method}'"
                )
                assert(
                    ex.error.contains("unexpected reply"),
                    s"expected 'unexpected reply' in error message but got '${ex.error}'"
                )
                assert(
                    ex.error.contains(wire),
                    s"expected raw wire preserved in error message but got '${ex.error}'"
                )
            case other => fail(s"expected Failure(BrowserProtocolErrorException) for unknown tag but got $other")
        }
    }

    "parseOutcome surfaces malformed JSON as BrowserProtocolErrorException (no panic)" in {
        val wire = "this is not json"
        Abort.run[BrowserReadException](StabilitySampler.parseOutcome(wire)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) =>
                assert(
                    ex.error.contains("unexpected reply"),
                    s"expected 'unexpected reply' in error message but got '${ex.error}'"
                )
            case other => fail(s"expected Failure(BrowserProtocolErrorException) for malformed wire but got $other")
        }
    }

    "parseOutcome surfaces wire missing required fields as BrowserProtocolErrorException" in {
        // Schema decoders require both `tag` and `value`; absence of `value` is a JS-template defect, not a sample.
        val wire = """{"tag":"stable"}"""
        Abort.run[BrowserReadException](StabilitySampler.parseOutcome(wire)).map {
            case Result.Failure(ex: BrowserProtocolErrorException) => assert(ex.method == "StabilitySampler.sampleWindow")
            case other => fail(s"expected Failure(BrowserProtocolErrorException) for missing field but got $other")
        }
    }

end StabilitySamplerTest
