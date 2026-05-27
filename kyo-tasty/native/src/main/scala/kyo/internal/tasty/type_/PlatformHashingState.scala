package kyo.internal.tasty.type_

import scala.collection.mutable

/** Native implementation: single-threaded, so a plain object-level var is sufficient. */
private[type_] object PlatformHashingState:

    private var state: mutable.HashSet[kyo.Tasty.Type] = new mutable.HashSet[kyo.Tasty.Type]()

    def get(): mutable.HashSet[kyo.Tasty.Type] = state

end PlatformHashingState
