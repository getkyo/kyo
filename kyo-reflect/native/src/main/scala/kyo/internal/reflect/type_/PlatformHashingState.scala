package kyo.internal.reflect.type_

import scala.collection.mutable

/** Native implementation: single-threaded, so a plain object-level var is sufficient. */
private[type_] object PlatformHashingState:

    private var state: mutable.HashSet[kyo.Reflect.Type] = new mutable.HashSet[kyo.Reflect.Type]()

    def get(): mutable.HashSet[kyo.Reflect.Type] = state

end PlatformHashingState
