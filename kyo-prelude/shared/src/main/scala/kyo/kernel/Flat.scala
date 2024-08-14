package kyo

import internal.FlatImplicits

opaque type Flat[T] = Null

object Flat extends FlatImplicits:
    object unsafe:
        inline given bypass[T]: Flat[T] = null
    end unsafe
end Flat
