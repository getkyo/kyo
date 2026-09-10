package kyo.kernel.internal

import kyo.Frame
import kyo.kernel.Arrow

// The stack-safety suspension threshold is platform-specific (smaller call stacks on Native
// and WASM need to suspend sooner); each platform sets it in kyo.internal.Platform.
// These two budgets are fixed rather than platform-specific.
private[kernel] inline def maxStackDepth  = 512
private[kernel] inline def maxTraceFrames = 64

// Rendering helpers for the node and arrow toStrings.
private[kernel] def short(v: Any): String =
    v match
        case v: Pending[?, ?]            => v.toString
        case v: Arrow.Chain[?, ?, ?, ?]  => v.toString
        case _: Arrow.Id[?]              => "Id"
        case _: Arrow.Transform[?, ?, ?] => "Transform"
        case v                           => v.toString

private[kernel] def site(frame: Frame): String =
    val callee = frame.calleeName
    if callee.isEmpty then s"${frame.callerName}(${frame.position.show})"
    else s"${frame.callerName}.$callee(${frame.position.show})"
end site
