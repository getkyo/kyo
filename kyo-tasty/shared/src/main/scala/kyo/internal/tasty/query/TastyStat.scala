package kyo.internal.tasty.query

import kyo.Stat

/** Global Stat scope for the kyo-tasty module. Use via `TastyStat.scope.traceSpan(name, attrs) { effect }`. */
private[kyo] object TastyStat:
    val scope: Stat = Stat.initScope("kyo-tasty")
