package kyo.internal

/** Named-constant table for CDP error-message substrings the resolver / eval translator inspect.
  *
  * CDP does not expose typed error codes for these conditions, so the only signal available is the human-readable `message` field. These
  * constants are substring-matched; a Chrome version that rewords its message would silently change the classification. Surfacing the
  * strings here makes a future grep find every site uniformly.
  */
private[kyo] object CdpErrorStrings:

    /** Emitted by `DOM.describeNode` and `DOM.resolveNode` when the supplied `nodeId` no longer exists (the DOM mutated under the resolver,
      * or the agent's per-document cache was invalidated). Triggers the stale-node retry path in [[Resolver]].
      */
    val StaleNodeErrorMessage: String = "Could not find node"

    /** Emitted by `Runtime.evaluate` when the supplied `executionContextId` no longer exists (the frame navigated or detached). Triggers
      * the context-destroyed translation in [[BrowserEval]].
      */
    val ContextDestroyedErrorMessage: String = "Cannot find context with specified id"

    /** Emitted by `Runtime.evaluate` when `returnByValue=true` is set but the result is a non-serialisable value (e.g. a JS Symbol or
      * a circular object). Triggers the unreturnable-value recovery path in [[CdpBackend.runtimeEvaluate]]: the error is re-encoded as a
      * fake wire JSON so that [[CdpEvalDecoder.isUnreturnableValueError]] can detect it and degrade to an empty-string result instead of
      * raising a typed abort.
      */
    val UnreturnableValueErrorMessage: String = "Object couldn't be returned by value"

end CdpErrorStrings
