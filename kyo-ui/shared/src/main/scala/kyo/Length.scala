package kyo

/** A dimensional value used for widths, padding, margins, gaps, offsets, and radii.
  *
  * Variants:
  *   - `Px(value)` — absolute pixels
  *   - `Pct(value)` — percentage of parent dimension
  *   - `Em(value)` — relative to font size (1em = 1 cell in TUI)
  *   - `Auto` — automatic sizing (fill parent or shrink-wrap content, depending on context)
  */
sealed abstract class Length derives CanEqual

object Length:
    final case class Px(value: Double)  extends Length
    final case class Pct(value: Double) extends Length
    final case class Em(value: Double)  extends Length
    case object Auto                    extends Length

    val zero: Px = Px(0)

    implicit def intToPx(v: Int): Px       = Px(v.toDouble)
    implicit def doubleToPx(v: Double): Px = Px(v)

    extension (v: Int)
        def px: Px   = Px(v.toDouble)
        def pct: Pct = Pct(v.toDouble)
        def em: Em   = Em(v.toDouble)
    end extension
    extension (v: Double)
        def px: Px   = Px(v)
        def pct: Pct = Pct(v)
        def em: Em   = Em(v)
    end extension

    /** Resolve a Length to pixels given the parent's pixel size. Auto fills the parent. Pct is relative to parent. Em = 1 cell in monospace
      * TUI.
      */
    def resolve(length: Length, parentPx: Int): Int = length match
        case Px(v)  => v.toInt
        case Pct(v) => (v * parentPx / 100).toInt
        case Em(v)  => v.toInt
        case Auto   => parentPx

    /** Resolve a Length, returning Absent for Auto (caller decides what Auto means). */
    def resolveOrAuto(length: Length, parentPx: Int): Maybe[Int] = length match
        case Auto  => kyo.Absent
        case other => kyo.Maybe(resolve(other, parentPx))

    /** Convert a Length to Px for fields that only support pixel values. Em is treated as pixels (1em = 1 cell in TUI). Pct and Auto become
      * zero.
      */
    def toPx(length: Length): Px = length match
        case px: Px => px
        case Em(v)  => Px(v)
        case Pct(_) => zero
        case Auto   => zero
end Length
