package kyo.proto.kernel.internal

import kyo.Frame
import kyo.proto.kernel.Arrow

private[kernel] inline def maxStackDepth = 512

private[kernel] inline def maxTraceFrames = 64

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
