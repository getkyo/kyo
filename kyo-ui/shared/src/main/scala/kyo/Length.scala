package kyo

import scala.language.implicitConversions

/** A dimensional value used for widths, padding, margins, gaps, offsets, and radii.
  *
  * #### Variants
  *
  *   - `Px(value: Double)`: absolute pixel length. Mapped to the CSS `px` unit. Accepted everywhere a `Length` is taken; negative values
  *     are clamped to 0 by helpers such as `clampSize`.
  *   - `Pct(value: Double)`: percentage of the parent dimension. Mapped to the CSS `%` unit. Accepted by `margin`, `width`, `height`,
  *     `minWidth`, `maxWidth`, `minHeight`, `maxHeight`, `rounded`, `translate`, and `padding`. Not accepted by methods typed as
  *     `Length.Px | Length.Em` (e.g. `gap`, `fontSize`, `letterSpacing`, `border` widths).
  *   - `Em(value: Double)`: relative to the element's font size. Mapped to the CSS `em` unit. Accepted wherever `Px` is accepted unless
  *     the signature is restricted to `Length.Px` only (border widths, shadows). Its numeric value is directly accessible via `.value`.
  *   - `Vh(value: Double)`: percentage of the viewport height. Mapped to the CSS `vh` unit. Used for heights tied to the visible window
  *     (e.g. a sticky rail capped to the viewport so it scrolls within itself rather than the page).
  *   - `Calc(expr: String)`: an arbitrary CSS `calc()` expression. Mapped to `calc(<expr>)` verbatim. Used for the rare value that mixes
  *     units the typed variants cannot express on their own, e.g. `Calc("100vh - 60px")` for a viewport-height box offset below a fixed
  *     header. The expression is not validated; callers pass a well-formed CSS calc body without the surrounding `calc(...)`.
  *   - `Auto`: automatic sizing; the browser fills or shrinks the dimension based on context. Accepted by `margin`, `width`, `height`,
  *     `minWidth`, `maxWidth`, `minHeight`, `maxHeight`. Not accepted by `padding`, `gap`, `fontSize`, `letterSpacing`, or any border-width
  *     parameter, all of which require a concrete `Px | Pct | Em` union.
  *
  * #### Implicit conversions
  *
  * Two implicit conversions are provided so that numeric literals can be used directly where a `Length.Px` is expected:
  *
  *   - `Int => Px`: fires when an `Int` literal is passed to a parameter of type `Length.Px` (e.g. `Style.width(100)`).
  *   - `Double => Px`: fires when a `Double` literal is passed similarly (e.g. `Style.width(12.5)`).
  *
  * #### Extension methods
  *
  * `Int` and `Double` values gain four suffix methods for explicit construction:
  *
  *   - `.px`: e.g. `16.px` produces `Length.Px(16.0)`
  *   - `.pct`: e.g. `50.pct` produces `Length.Pct(50.0)`
  *   - `.em`: e.g. `1.5.em` produces `Length.Em(1.5)`
  *   - `.vh`: e.g. `100.vh` produces `Length.Vh(100.0)`
  *
  * #### Resolution helpers
  *
  *   - `resolve(length, parentPx)`: converts any variant to an `Int` pixel count. `Auto` fills the parent (`Auto` maps to `parentPx`). `Pct` is
  *     relative to `parentPx`. `Em` is treated as pixels (1 em = 1 px).
  *   - `resolveOrAuto(length, parentPx)`: same as `resolve` but returns `Absent` for `Auto`, letting the caller decide what "auto" means
  *     in its context.
  */
sealed abstract class Length derives CanEqual

extension (v: Int)
    def px: Length.Px   = Length.Px(v.toDouble)
    def pct: Length.Pct = Length.Pct(v.toDouble)
    def em: Length.Em   = Length.Em(v.toDouble)
    def vh: Length.Vh   = Length.Vh(v.toDouble)
end extension
extension (v: Double)
    def px: Length.Px   = Length.Px(v)
    def pct: Length.Pct = Length.Pct(v)
    def em: Length.Em   = Length.Em(v)
    def vh: Length.Vh   = Length.Vh(v)
end extension

object Length:
    final case class Px(value: Double)  extends Length
    final case class Pct(value: Double) extends Length
    final case class Em(value: Double)  extends Length
    final case class Vh(value: Double)  extends Length
    final case class Calc(expr: String) extends Length
    case object Auto                    extends Length

    /** Zero pixels (`Px(0)`). */
    val zero: Px = Px(0)

    /** Auto-lifts an `Int` literal to `Px`, so a bare number can be passed where a `Length.Px` is expected (e.g. `Style.width(100)`). */
    implicit def intToPx(v: Int): Px = Px(v.toDouble)

    /** Auto-lifts a `Double` literal to `Px`, so a bare number can be passed where a `Length.Px` is expected (e.g. `Style.width(12.5)`). */
    implicit def doubleToPx(v: Double): Px = Px(v)

    /** Resolve a Length to pixels given the parent's pixel size. Auto fills the parent. Pct and Vh are taken relative to `parentPx` (there is
      * no separate viewport dimension at this layer, so `Vh` approximates against the parent like `Pct`). Em is treated as pixels (1 em maps
      * to 1 px). Calc is opaque (its expression is not parsed here), so it resolves to `parentPx` as a neutral fallback.
      */
    def resolve(length: Length, parentPx: Int): Int = length match
        case Px(v)   => v.toInt
        case Pct(v)  => (v * parentPx / 100).toInt
        case Em(v)   => v.toInt
        case Vh(v)   => (v * parentPx / 100).toInt
        case Calc(_) => parentPx
        case Auto    => parentPx

    /** Resolve a Length, returning Absent for Auto (caller decides what Auto means). */
    def resolveOrAuto(length: Length, parentPx: Int): Maybe[Int] = length match
        case Auto  => kyo.Absent
        case other => kyo.Maybe(resolve(other, parentPx))

end Length
