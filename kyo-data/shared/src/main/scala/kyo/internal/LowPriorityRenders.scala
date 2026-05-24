package kyo.internal

import kyo.Render
import kyo.Text

private[kyo] trait LowPriorityRenders:
    given [A]: Render[A] with
        def asText(value: A): Text = value.toString
end LowPriorityRenders
