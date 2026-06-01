package kyo

class BrowserExceptionHierarchyTest extends Test:

    // ---- Read-row topical-category pins ----

    "BrowserConnectionLostException extends BrowserConnectionException with BrowserReadException" in {
        discard(summon[BrowserConnectionLostException <:< BrowserConnectionException])
        discard(summon[BrowserConnectionLostException <:< BrowserReadException])
        succeed
    }

    "BrowserProtocolErrorException extends BrowserConnectionException with BrowserReadException" in {
        discard(summon[BrowserProtocolErrorException <:< BrowserConnectionException])
        discard(summon[BrowserProtocolErrorException <:< BrowserReadException])
        succeed
    }

    "BrowserIFrameInvalidException extends BrowserIFrameException with BrowserReadException" in {
        discard(summon[BrowserIFrameInvalidException <:< BrowserIFrameException])
        discard(summon[BrowserIFrameInvalidException <:< BrowserReadException])
        succeed
    }

    "BrowserNavigationFailedException extends BrowserNavigationException with BrowserReadException" in {
        discard(summon[BrowserNavigationFailedException <:< BrowserNavigationException])
        discard(summon[BrowserNavigationFailedException <:< BrowserReadException])
        succeed
    }

    "BrowserScriptErrorException extends BrowserScriptException with BrowserReadException" in {
        discard(summon[BrowserScriptErrorException <:< BrowserScriptException])
        discard(summon[BrowserScriptErrorException <:< BrowserReadException])
        succeed
    }

    // ---- BrowserDecodingException folds directly into the read row ----

    "BrowserDecodingException extends BrowserReadException" in {
        discard(summon[BrowserDecodingException <:< BrowserReadException])
        succeed
    }

    // ---- element-not-found is element + mutation + assertion ----

    "BrowserElementNotFoundException extends BrowserElementException with BrowserMutationException with BrowserAssertionException" in {
        discard(summon[BrowserElementNotFoundException <:< BrowserElementException])
        discard(summon[BrowserElementNotFoundException <:< BrowserMutationException])
        discard(summon[BrowserElementNotFoundException <:< BrowserAssertionException])
        succeed
    }

    // ---- element-not-actionable is mutation only ----

    "BrowserElementNotActionableException extends BrowserElementException with BrowserMutationException" in {
        discard(summon[BrowserElementNotActionableException <:< BrowserElementException])
        discard(summon[BrowserElementNotActionableException <:< BrowserMutationException])
        succeed
    }

    // ---- assertion-timed-out is an assertion failure and also extends BrowserMutationException ----

    "BrowserAssertionTimedOutException extends BrowserAssertionException with BrowserMutationException" in {
        discard(summon[BrowserAssertionTimedOutException <:< BrowserAssertionException])
        discard(summon[BrowserAssertionTimedOutException <:< BrowserMutationException])
        succeed
    }

    // ---- linear ordering: BrowserReadException ⊂ BrowserMutationException; BrowserAssertionException ⊂ BrowserMutationException ----

    "BrowserReadException ⊂ BrowserMutationException; BrowserAssertionException ⊂ BrowserMutationException (linear ordering)" in {
        discard(summon[BrowserReadException <:< Object])
        discard(summon[BrowserMutationException <:< BrowserReadException])
        discard(summon[BrowserAssertionException <:< BrowserMutationException])
        succeed
    }

    // ---- BrowserInvalidArgumentException is a read-row API-misuse exception ----

    "BrowserInvalidArgumentException extends BrowserReadException with BrowserException" in {
        discard(summon[BrowserInvalidArgumentException <:< BrowserReadException])
        discard(summon[BrowserInvalidArgumentException <:< BrowserException])
        succeed
    }

    // ---- Reason types nested under their exception ----

    "BrowserElementNotActionableException.Reason nested type: all expected cases are present" in {
        val reasons: Seq[BrowserElementNotActionableException.Reason] = Seq(
            BrowserElementNotActionableException.Reason.NotAttached,
            BrowserElementNotActionableException.Reason.NotVisible(
                BrowserElementNotActionableException.Reason.NotVisibleCause.DisplayNone
            ),
            BrowserElementNotActionableException.Reason.NotVisible(
                BrowserElementNotActionableException.Reason.NotVisibleCause.VisibilityHidden
            ),
            BrowserElementNotActionableException.Reason.NotVisible(
                BrowserElementNotActionableException.Reason.NotVisibleCause.OpacityZero
            ),
            BrowserElementNotActionableException.Reason.NotVisible(
                BrowserElementNotActionableException.Reason.NotVisibleCause.ZeroComputedSize
            ),
            BrowserElementNotActionableException.Reason.Disabled(
                BrowserElementNotActionableException.Reason.DisabledKind.Attribute
            ),
            BrowserElementNotActionableException.Reason.Disabled(
                BrowserElementNotActionableException.Reason.DisabledKind.AriaDisabled
            ),
            BrowserElementNotActionableException.Reason.Disabled(
                BrowserElementNotActionableException.Reason.DisabledKind.FieldsetDisabled
            ),
            BrowserElementNotActionableException.Reason.Disabled(
                BrowserElementNotActionableException.Reason.DisabledKind.PointerEventsNone
            ),
            BrowserElementNotActionableException.Reason.NotInViewport(
                BrowserElementNotActionableException.Reason.Rect(0, 0, 1, 1),
                BrowserElementNotActionableException.Reason.Rect(0, 0, 100, 100)
            ),
            BrowserElementNotActionableException.Reason.ZeroSizedElement(0, 0),
            BrowserElementNotActionableException.Reason.OutsideHitTarget("div"),
            BrowserElementNotActionableException.Reason.NotFillable("div"),
            BrowserElementNotActionableException.Reason.Unstable,
            BrowserElementNotActionableException.Reason.FillDesync
        )
        assert(reasons.size == 15)
        assert(reasons.forall(_.description.nonEmpty))
    }

    "BrowserIFrameInvalidException.Reason nested type: all expected cases are present" in {
        val reasons: Seq[BrowserIFrameInvalidException.Reason] = Seq(
            BrowserIFrameInvalidException.Reason.NotAFrame,
            BrowserIFrameInvalidException.Reason.ContextNotObserved,
            BrowserIFrameInvalidException.Reason.ContextDestroyed,
            BrowserIFrameInvalidException.Reason.RootNotSeeded
        )
        assert(reasons.size == 4)
        assert(reasons.forall(_.describe.nonEmpty))
    }

end BrowserExceptionHierarchyTest
