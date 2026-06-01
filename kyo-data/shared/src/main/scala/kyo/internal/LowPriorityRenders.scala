package kyo.internal

import kyo.Render

private[kyo] trait LowPriorityRenders:
    given [A]: Render[A] with
        def asString(value: A): String = value.toString
end LowPriorityRenders
