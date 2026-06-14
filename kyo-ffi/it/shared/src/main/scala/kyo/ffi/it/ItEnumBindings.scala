package kyo.ffi.it

import kyo.AllowUnsafe
import kyo.ffi.*

enum ItColor(val value: Int) derives CanEqual:
    case Red   extends ItColor(0)
    case Green extends ItColor(1)
    case Blue  extends ItColor(2)
end ItColor

object ItColor:
    def fromInt(v: Int): ItColor = ItColor.values.find(_.value == v)
        .getOrElse(throw new IllegalArgumentException(s"Unknown ItColor: $v"))

trait ItEnumBindings extends Ffi:
    def kyo_it_color_value(c: ItColor)(using AllowUnsafe): Int
    def kyo_it_color_get(index: Int)(using AllowUnsafe): ItColor
    def kyo_it_next_color(c: ItColor)(using AllowUnsafe): ItColor
end ItEnumBindings

object ItEnumBindings extends Ffi.Config(library = "kyo_it_bundled")
