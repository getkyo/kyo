package kyo.internal

import kyo.*
import kyo.internal.CdpTypes.*
import kyo.internal.cdp.PageDownload

class CdpTypesSchemaFailureTest extends kyo.Test:

    // MouseEventType / KeyEventType / PageDownload.Behavior Schemas used to throw a raw
    // IllegalArgumentException on an unknown wire value inside Schema.stringSchema.transform. That throw
    // is not a DecodeException, so Result.catching[DecodeException] inside Json.decode surfaces it as
    // Result.Panic rather than the typed Result.Failure callers handle.
    // Decoding a wire value not in the enum's vocabulary must yield Result.Failure[DecodeException].

    "MouseEventType - unknown wire value decodes as Result.Failure[UnknownVariantException]" in {
        Json.decode[MouseEventType]("\"moonwalk\"") match
            case Result.Failure(ex: UnknownVariantException) =>
                assert(
                    ex.variantName.contains("moonwalk"),
                    s"expected variantName to mention 'moonwalk', got '${ex.variantName}'"
                )
            case other => fail(s"expected Result.Failure(UnknownVariantException) for unknown MouseEventType wire 'moonwalk', got $other")
    }

    "MouseEventType - known wire values still decode" in {
        Json.decode[MouseEventType]("\"mouseMoved\"") match
            case Result.Success(MouseEventType.Moved) => succeed
            case other                                => fail(s"expected Success(Moved), got $other")
    }

    "KeyEventType - unknown wire value decodes as Result.Failure[UnknownVariantException]" in {
        Json.decode[KeyEventType]("\"chord\"") match
            case Result.Failure(ex: UnknownVariantException) =>
                assert(
                    ex.variantName.contains("chord"),
                    s"expected variantName to mention 'chord', got '${ex.variantName}'"
                )
            case other => fail(s"expected Result.Failure(UnknownVariantException) for unknown KeyEventType wire 'chord', got $other")
    }

    "KeyEventType - known wire values still decode" in {
        Json.decode[KeyEventType]("\"keyDown\"") match
            case Result.Success(KeyEventType.Down) => succeed
            case other                             => fail(s"expected Success(Down), got $other")
    }

    "PageDownload.Behavior - unknown wire value decodes as Result.Failure[UnknownVariantException]" in {
        Json.decode[PageDownload.Behavior]("\"orbital\"") match
            case Result.Failure(ex: UnknownVariantException) =>
                assert(
                    ex.variantName.contains("orbital"),
                    s"expected variantName to mention 'orbital', got '${ex.variantName}'"
                )
            case other => fail(s"expected Result.Failure(UnknownVariantException) for unknown Behavior wire 'orbital', got $other")
    }

    "PageDownload.Behavior - known wire values still decode" in {
        Json.decode[PageDownload.Behavior]("\"allow\"") match
            case Result.Success(PageDownload.Behavior.Allow) => succeed
            case other                                       => fail(s"expected Success(Allow), got $other")
    }

end CdpTypesSchemaFailureTest
