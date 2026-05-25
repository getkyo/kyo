package kyo.internal

import scala.quoted.*

/** Scala.js placeholder for the cross-build platform-symbol shadow. Returns the empty set
  * because no JS-only `Schema` givens require gate registration; the JVM shadow at
  * `kyo-schema/jvm/src/main/scala/kyo/internal/PlatformSymbols.scala` populates the real set.
  */
private[internal] object PlatformSymbols:

    def primitiveSymbols(using Quotes): Set[quotes.reflect.Symbol] =
        Set.empty
    end primitiveSymbols
end PlatformSymbols
