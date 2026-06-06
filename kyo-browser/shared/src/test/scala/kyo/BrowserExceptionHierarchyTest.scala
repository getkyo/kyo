package kyo

class BrowserExceptionHierarchyTest extends BaseBrowserTest:

    // ---- Read-row topical-category pins ----

    "BrowserConnectionLostException extends BrowserConnectionException with BrowserReadException" in {
        discard(summon[BrowserConnectionLostException <:< BrowserConnectionException])
        discard(summon[BrowserConnectionLostException <:< BrowserReadException])
        succeed("BrowserConnectionLostException is a BrowserConnectionException and a BrowserReadException")
    }

    "BrowserProtocolErrorException extends BrowserConnectionException with BrowserReadException" in {
        discard(summon[BrowserProtocolErrorException <:< BrowserConnectionException])
        discard(summon[BrowserProtocolErrorException <:< BrowserReadException])
        succeed("BrowserProtocolErrorException is a BrowserConnectionException and a BrowserReadException")
    }

    "BrowserIFrameInvalidException extends BrowserIFrameException with BrowserReadException" in {
        discard(summon[BrowserIFrameInvalidException <:< BrowserIFrameException])
        discard(summon[BrowserIFrameInvalidException <:< BrowserReadException])
        succeed("BrowserIFrameInvalidException is a BrowserIFrameException and a BrowserReadException")
    }

    "BrowserNavigationFailedException extends BrowserNavigationException with BrowserReadException" in {
        discard(summon[BrowserNavigationFailedException <:< BrowserNavigationException])
        discard(summon[BrowserNavigationFailedException <:< BrowserReadException])
        succeed("BrowserNavigationFailedException is a BrowserNavigationException and a BrowserReadException")
    }

    "BrowserScriptErrorException extends BrowserScriptException with BrowserReadException" in {
        discard(summon[BrowserScriptErrorException <:< BrowserScriptException])
        discard(summon[BrowserScriptErrorException <:< BrowserReadException])
        succeed("BrowserScriptErrorException is a BrowserScriptException and a BrowserReadException")
    }

    // ---- BrowserDecodingException folds directly into the read row ----

    "BrowserDecodingException extends BrowserReadException" in {
        discard(summon[BrowserDecodingException <:< BrowserReadException])
        succeed("BrowserDecodingException is a BrowserReadException")
    }

    // ---- element-not-found is element + mutation + assertion ----

    "BrowserElementNotFoundException extends BrowserElementException with BrowserMutationException with BrowserAssertionException" in {
        discard(summon[BrowserElementNotFoundException <:< BrowserElementException])
        discard(summon[BrowserElementNotFoundException <:< BrowserMutationException])
        discard(summon[BrowserElementNotFoundException <:< BrowserAssertionException])
        succeed("BrowserElementNotFoundException is an element, mutation, and assertion exception")
    }

    // ---- element-not-actionable is mutation only ----

    "BrowserElementNotActionableException extends BrowserElementException with BrowserMutationException" in {
        discard(summon[BrowserElementNotActionableException <:< BrowserElementException])
        discard(summon[BrowserElementNotActionableException <:< BrowserMutationException])
        succeed("BrowserElementNotActionableException is an element and mutation exception")
    }

    // ---- assertion-timed-out is an assertion failure and also extends BrowserMutationException ----

    "BrowserAssertionTimedOutException extends BrowserAssertionException with BrowserMutationException" in {
        discard(summon[BrowserAssertionTimedOutException <:< BrowserAssertionException])
        discard(summon[BrowserAssertionTimedOutException <:< BrowserMutationException])
        succeed("BrowserAssertionTimedOutException is an assertion and mutation exception")
    }

    // ---- linear ordering: BrowserReadException ⊂ BrowserMutationException; BrowserAssertionException ⊂ BrowserMutationException ----

    "BrowserReadException ⊂ BrowserMutationException; BrowserAssertionException ⊂ BrowserMutationException (linear ordering)" in {
        // The marker traits layer so that a single BrowserReadException handler catches mutation and assertion failures.
        discard(summon[BrowserMutationException <:< BrowserReadException])
        discard(summon[BrowserAssertionException <:< BrowserMutationException])
        succeed("mutation and assertion exceptions linearize under BrowserReadException")
    }

    // ---- BrowserInvalidArgumentException is a read-row API-misuse exception ----

    "BrowserInvalidArgumentException extends BrowserReadException with BrowserException" in {
        discard(summon[BrowserInvalidArgumentException <:< BrowserReadException])
        discard(summon[BrowserInvalidArgumentException <:< BrowserException])
        succeed("BrowserInvalidArgumentException is a BrowserReadException and a BrowserException")
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
