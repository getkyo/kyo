package kyo.internal

import kyo.Render

private[kyo] trait LowPriorityRenders:
    given [A]: Render[A] with
        // String.valueOf (not value.toString) so a null value renders as "null" instead of throwing an NPE.
        def asString(value: A): String = String.valueOf(value)
end LowPriorityRenders
