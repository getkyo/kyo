package kyo

import kyo.UI.Ast.*
import kyo.internal.CssStyleRenderer
import kyo.internal.NumberFormat

/** The SVG namespace: every SVG element factory, the sealed `SvgElement` AST, the
  * capability traits, the typed value DSLs, and the constrained enums.
  *
  * Import `kyo.*` brings `Svg` into scope; write SVG content qualified (`Svg.circle`,
  * `Svg.rect`, `Svg.path`). A chart author may `import kyo.Svg.*` to drop the qualifier.
  * The `<svg>` root is reached as `Svg.svg(...)` and, because it is `HtmlContent`,
  * embeds directly in any HTML container: `div(Svg.svg(...))`.
  *
  * SVG elements are `UI` `Element`s so they reuse the path/event/reactive engine,
  * but only `Svg.Root` is also `HtmlContent`: `div(Svg.circle(...))` does not
  * compile. Geometry coordinates take plain `Double`/`Int`; the units/percent
  * minority takes `Svg.SvgLength`. Paint/clip/mask/marker references are typed handles
  * (`Svg.Paint.Ref`, ...), never raw `url(#id)` strings.
  */
object Svg:

    // ---- factories: structure ----
    def svg(using Frame): Root                = Root()
    def g(using Frame): G                     = G()
    def defs(using Frame): Defs               = Defs()
    def symbol(using Frame): Symbol           = Symbol()
    def switch(using Frame): Switch           = Switch()
    def a(using Frame): SvgAnchor             = SvgAnchor()
    def use(target: Symbol)(using Frame): Use = Use(SvgAttrs(href = Present(s"#${target.svgAttrs.defId.getOrElse(genId(target.frame))}")))
    def use(target: SvgElement & HasId)(using Frame): Use =
        Use(SvgAttrs(href = Present(s"#${target.svgAttrs.defId.getOrElse(genId(target.frame))}")))

    // ---- factories: shapes ----
    def rect(using Frame): Rect         = Rect()
    def circle(using Frame): Circle     = Circle()
    def ellipse(using Frame): Ellipse   = Ellipse()
    def line(using Frame): Line         = Line()
    def polyline(using Frame): Polyline = Polyline()
    def polygon(using Frame): Polygon   = Polygon()
    def path(using Frame): Path         = Path()

    // ---- factories: text ----
    def text(using Frame): Text   = Text()
    def tspan(using Frame): TSpan = TSpan()
    def textPath(target: Path)(using Frame): TextPath =
        TextPath(SvgAttrs(href = Present(s"#${target.svgAttrs.defId.getOrElse(genId(target.frame))}")))

    // ---- factories: gradients/patterns ----
    def linearGradient(using Frame): LinearGradient = LinearGradient()
    def radialGradient(using Frame): RadialGradient = RadialGradient()
    def stop(using Frame): Stop                     = Stop()
    def pattern(using Frame): Pattern               = Pattern()

    // ---- factories: clip/mask ----
    def clipPath(using Frame): ClipPath = ClipPath()
    def mask(using Frame): Mask         = Mask()

    // ---- factories: image/foreignObject ----
    def image(href: UI.ImgSrc)(using Frame): Image =
        val s = href match
            case UI.ImgSrc.Absolute(u)  => u.full
            case UI.ImgSrc.Path(p)      => p
            case UI.ImgSrc.Data(m, pay) => s"data:$m;base64,$pay"
        Image(SvgAttrs(href = Present(s)))
    end image
    def foreignObject(using Frame): ForeignObject = ForeignObject()

    // ---- factories: marker ----
    def marker(using Frame): Marker = Marker()

    // ---- factories: metadata ----
    def title(text: String)(using Frame): Title = Title(text)
    def desc(text: String)(using Frame): Desc   = Desc(text)
    def metadata(using Frame): Metadata         = Metadata()

    // ---- factories: filters ----
    def filter(using Frame): Filter                       = Filter()
    def feGaussianBlur(using Frame): FeGaussianBlur       = FeGaussianBlur()
    def feOffset(using Frame): FeOffset                   = FeOffset()
    def feBlend(using Frame): FeBlend                     = FeBlend()
    def feColorMatrix(using Frame): FeColorMatrix         = FeColorMatrix()
    def feFlood(using Frame): FeFlood                     = FeFlood()
    def feComposite(using Frame): FeComposite             = FeComposite()
    def feMerge(using Frame): FeMerge                     = FeMerge()
    def feMergeNode(using Frame): FeMergeNode             = FeMergeNode()
    def feImage(using Frame): FeImage                     = FeImage()
    def feTile(using Frame): FeTile                       = FeTile()
    def feMorphology(using Frame): FeMorphology           = FeMorphology()
    def feTurbulence(using Frame): FeTurbulence           = FeTurbulence()
    def feDisplacementMap(using Frame): FeDisplacementMap = FeDisplacementMap()

    // ---- factories: SMIL animation ----

    /** Builds a `<animate>` element that animates a single SVG attribute over time. */
    def animate(using Frame): Animate = Animate()

    /** Builds a `<animateTransform>` element that animates a transform attribute
      * (translate, scale, rotate, skewX, skewY) on the parent element over time.
      */
    def animateTransform(using Frame): AnimateTransform = AnimateTransform()

    /** Builds a `<animateMotion>` element that moves the parent element along a path. */
    def animateMotion(using Frame): AnimateMotion = AnimateMotion()

    /** Builds a `<set>` animation element. Named `SetAnim` in the API to avoid clashing
      * with `scala.collection.Set`; this factory is the canonical entry point.
      */
    def set(using Frame): SetAnim = SetAnim()

    // ---- AST root ----

    /** The sealed root of the SVG element AST. Every element extends it, so the
      * renderer's tag/attribute dispatch and the engine's rebuild are exhaustive (a new
      * element forces a handler in each match).
      */
    sealed trait SvgElement extends UI.Ast.SvgNode:
        type Self <: SvgElement
        private[kyo] def svgAttrs: SvgAttrs
        private[kyo] def withSvg(s: SvgAttrs): Self
    end SvgElement

    /** Sealed sub-trait for filter primitives; the content-model bound for `filter`. */
    sealed trait FilterPrimitive extends SvgElement

    // ---- Value DSLs ----

    /** An SVG length with an explicit unit. `User` is unitless user space; no `Auto`. */
    sealed abstract class SvgLength derives CanEqual
    object SvgLength:
        final case class User(value: Double) extends SvgLength
        final case class Px(value: Double)   extends SvgLength
        final case class Pct(value: Double)  extends SvgLength
        final case class Em(value: Double)   extends SvgLength
        def user(v: Double): SvgLength = User(v)
        def px(v: Double): SvgLength   = Px(v)
        def pct(v: Double): SvgLength  = Pct(v)
        def em(v: Double): SvgLength   = Em(v)
    end SvgLength

    /** The `private[kyo]` command ADT backing the opaque `PathData`. */
    private[kyo] enum PathCommand derives CanEqual:
        case MoveTo(x: Double, y: Double)
        case MoveBy(dx: Double, dy: Double)
        case LineTo(x: Double, y: Double)
        case LineBy(dx: Double, dy: Double)
        case HLineTo(x: Double)
        case HLineBy(dx: Double)
        case VLineTo(y: Double)
        case VLineBy(dy: Double)
        case CubicTo(c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double)
        case CubicBy(c1x: Double, c1y: Double, c2x: Double, c2y: Double, dx: Double, dy: Double)
        case SmoothCubicTo(c2x: Double, c2y: Double, x: Double, y: Double)
        case SmoothCubicBy(c2x: Double, c2y: Double, dx: Double, dy: Double)
        case QuadTo(cx: Double, cy: Double, x: Double, y: Double)
        case QuadBy(cx: Double, cy: Double, dx: Double, dy: Double)
        case SmoothQuadTo(x: Double, y: Double)
        case SmoothQuadBy(dx: Double, dy: Double)
        case ArcTo(rx: Double, ry: Double, xRot: Double, largeArc: Boolean, sweep: Boolean, x: Double, y: Double)
        case ArcBy(rx: Double, ry: Double, xRot: Double, largeArc: Boolean, sweep: Boolean, dx: Double, dy: Double)
        case Close
    end PathCommand

    /** A typed path-command list backing the `d` attribute; no raw `d` string. */
    opaque type PathData = Chunk[PathCommand]
    object PathData:
        def from(x: Double, y: Double): PathData                   = Chunk(PathCommand.MoveTo(x, y))
        def from(x: Int, y: Int): PathData                         = from(x.toDouble, y.toDouble)
        val empty: PathData                                        = Chunk.empty
        private[kyo] def commands(p: PathData): Chunk[PathCommand] = p
        given CanEqual[PathData, PathData]                         = CanEqual.derived
    end PathData

    extension (p: PathData)
        def moveTo(x: Double, y: Double): PathData   = p.appended(PathCommand.MoveTo(x, y))
        def moveBy(dx: Double, dy: Double): PathData = p.appended(PathCommand.MoveBy(dx, dy))
        def lineTo(x: Double, y: Double): PathData   = p.appended(PathCommand.LineTo(x, y))
        def lineBy(dx: Double, dy: Double): PathData = p.appended(PathCommand.LineBy(dx, dy))
        def hLineTo(x: Double): PathData             = p.appended(PathCommand.HLineTo(x))
        def hLineBy(dx: Double): PathData            = p.appended(PathCommand.HLineBy(dx))
        def vLineTo(y: Double): PathData             = p.appended(PathCommand.VLineTo(y))
        def vLineBy(dy: Double): PathData            = p.appended(PathCommand.VLineBy(dy))
        def cubicTo(c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double): PathData =
            p.appended(PathCommand.CubicTo(c1x, c1y, c2x, c2y, x, y))
        def cubicBy(c1x: Double, c1y: Double, c2x: Double, c2y: Double, dx: Double, dy: Double): PathData =
            p.appended(PathCommand.CubicBy(c1x, c1y, c2x, c2y, dx, dy))
        def smoothCubicTo(c2x: Double, c2y: Double, x: Double, y: Double): PathData =
            p.appended(PathCommand.SmoothCubicTo(c2x, c2y, x, y))
        def smoothCubicBy(c2x: Double, c2y: Double, dx: Double, dy: Double): PathData =
            p.appended(PathCommand.SmoothCubicBy(c2x, c2y, dx, dy))
        def quadTo(cx: Double, cy: Double, x: Double, y: Double): PathData =
            p.appended(PathCommand.QuadTo(cx, cy, x, y))
        def quadBy(cx: Double, cy: Double, dx: Double, dy: Double): PathData =
            p.appended(PathCommand.QuadBy(cx, cy, dx, dy))
        def smoothQuadTo(x: Double, y: Double): PathData   = p.appended(PathCommand.SmoothQuadTo(x, y))
        def smoothQuadBy(dx: Double, dy: Double): PathData = p.appended(PathCommand.SmoothQuadBy(dx, dy))
        def arcTo(rx: Double, ry: Double, xRot: Double, largeArc: Boolean, sweep: Boolean, x: Double, y: Double): PathData =
            p.appended(PathCommand.ArcTo(rx, ry, xRot, largeArc, sweep, x, y))
        def arcBy(rx: Double, ry: Double, xRot: Double, largeArc: Boolean, sweep: Boolean, dx: Double, dy: Double): PathData =
            p.appended(PathCommand.ArcBy(rx, ry, xRot, largeArc, sweep, dx, dy))
        def close: PathData = p.appended(PathCommand.Close)
    end extension

    /** An opaque point sequence backing `points`; no raw `"x,y"` string. */
    opaque type Points = Chunk[(Double, Double)]
    object Points:
        def apply(ps: (Double, Double)*): Points                   = Chunk.from(ps)
        def from(ps: Seq[(Double, Double)]): Points                = Chunk.from(ps)
        val empty: Points                                          = Chunk.empty
        private[kyo] def pairs(p: Points): Chunk[(Double, Double)] = p
    end Points

    /** A typed `transform` operation; absent axes use `Maybe` per Principle 4. */
    sealed abstract class Transform derives CanEqual
    object Transform:
        final case class Translate(x: Double, y: Double = 0.0)                                       extends Transform
        final case class Rotate(deg: Double, cx: Maybe[Double] = Absent, cy: Maybe[Double] = Absent) extends Transform
        final case class Scale(sx: Double, sy: Maybe[Double] = Absent)                               extends Transform
        final case class SkewX(deg: Double)                                                          extends Transform
        final case class SkewY(deg: Double)                                                          extends Transform
        final case class Matrix(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double)    extends Transform
    end Transform

    /** A paint value: `none | currentColor | <color> | url(#id)`. */
    sealed abstract class Paint derives CanEqual
    object Paint:
        case object None                           extends Paint
        case object CurrentColor                   extends Paint
        final case class Color(value: Style.Color) extends Paint
        final case class Ref(server: PaintServer)  extends Paint
        given Conversion[Style.Color, Paint] = Color(_)
    end Paint

    /** A definition element that is referenced by id (gradient/pattern/clipPath/mask/marker/filter).
      * Carries the deterministic `id` used both when materializing a reference handle and when the
      * renderer emits the element's `id` attribute, so a raw (no explicit `id`) definition element
      * referenced via its `*Ref`/`.paint` handle still emits a matching `id` (never a dangling `url(#id)`).
      */
    sealed trait DefinitionElement extends SvgElement:
        private[kyo] def id: String

    /** The carrier of a generated paint-server id; produced only by `.paint` on a
      * gradient/pattern, never constructed by users.
      */
    sealed trait PaintServer extends DefinitionElement

    /** A typed `viewBox`; not a `"0 0 w h"` string. */
    final case class ViewBox(minX: Double, minY: Double, width: Double, height: Double) derives CanEqual

    /** A typed `preserveAspectRatio`. */
    final case class PreserveAspectRatio(align: Align, meetOrSlice: MeetOrSlice = MeetOrSlice.Meet) derives CanEqual

    enum Align derives CanEqual:
        case None, XMinYMin, XMidYMin, XMaxYMin, XMinYMid, XMidYMid, XMaxYMid, XMinYMax, XMidYMax, XMaxYMax
    enum MeetOrSlice derives CanEqual:
        case Meet, Slice

    // ---- Constrained enums ----

    enum FillRule derives CanEqual:
        case NonZero, EvenOdd
    end FillRule

    enum ClipRule derives CanEqual:
        case NonZero, EvenOdd
    end ClipRule

    enum StrokeLinecap derives CanEqual:
        case Butt, Round, Square
    end StrokeLinecap

    enum StrokeLinejoin derives CanEqual:
        case Miter, Round, Bevel, Arcs, MiterClip
    end StrokeLinejoin

    enum TextAnchor derives CanEqual:
        case Start, Middle, End
    end TextAnchor

    enum DominantBaseline derives CanEqual:
        case Auto, Middle, Central, Hanging, TextBeforeEdge, TextAfterEdge, Alphabetic, Ideographic, Mathematical
    end DominantBaseline

    enum SpreadMethod derives CanEqual:
        case Pad, Reflect, Repeat
    end SpreadMethod

    enum MarkerUnits derives CanEqual:
        case StrokeWidth, UserSpaceOnUse
    end MarkerUnits

    enum Visibility derives CanEqual:
        case Visible, Hidden, Collapse
    end Visibility

    /** Shared two-member units enum reused for gradient/clip/mask/pattern/filter units. */
    enum Units derives CanEqual:
        case UserSpaceOnUse, ObjectBoundingBox
    end Units

    type GradientUnits = Units

    /** `feBlend` blend `mode`. Cases render to their exact SVG tokens (e.g. `ColorDodge -> "color-dodge"`). */
    enum BlendMode derives CanEqual:
        case Normal, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn, HardLight, SoftLight,
            Difference, Exclusion, Hue, Saturation, Color, Luminosity
    end BlendMode

    /** `feColorMatrix` `type`. Cases render to their exact SVG tokens (e.g. `HueRotate -> "hueRotate"`). */
    enum ColorMatrixType derives CanEqual:
        case Matrix, Saturate, HueRotate, LuminanceToAlpha
    end ColorMatrixType

    /** `feComposite` `operator`. */
    enum CompositeOperator derives CanEqual:
        case Over, In, Out, Atop, Xor, Arithmetic
    end CompositeOperator

    /** `feMorphology` `operator`. */
    enum MorphologyOperator derives CanEqual:
        case Erode, Dilate
    end MorphologyOperator

    /** `feTurbulence` `type`. Cases render to their exact SVG tokens (e.g. `FractalNoise -> "fractalNoise"`). */
    enum TurbulenceType derives CanEqual:
        case FractalNoise, Turbulence
    end TurbulenceType

    /** `animateTransform` `type`: the kind of transform the animation interpolates. */
    enum TransformType derives CanEqual:
        case Translate, Scale, Rotate, SkewX, SkewY
    end TransformType

    /** `animate` `calcMode`: the interpolation mode between keyframe values. Cases render to their lowercase SVG
      * tokens (`Discrete -> "discrete"`, `Spline -> "spline"`, ...). `Spline` is the mode that activates
      * `keySplines`.
      */
    enum CalcMode derives CanEqual:
        case Discrete, Linear, Paced, Spline
    end CalcMode

    private def calcModeToken(v: CalcMode): String = v match
        case CalcMode.Discrete => "discrete"
        case CalcMode.Linear   => "linear"
        case CalcMode.Paced    => "paced"
        case CalcMode.Spline   => "spline"

    // ---- SVG attribute bag ----

    /** Typed presentation/geometry attributes for an SVG element. Separate from CSS
      * `Style` and from HTML `Attrs`; the shared `Attrs` bag still carries id/events.
      */
    final private[kyo] case class SvgAttrs(
        // shared presentation
        fill: Maybe[Paint] = Absent,
        fillOpacity: Maybe[Double] = Absent,
        fillRule: Maybe[FillRule] = Absent,
        stroke: Maybe[Paint] = Absent,
        strokeWidth: Maybe[SvgLength] = Absent,
        strokeOpacity: Maybe[Double] = Absent,
        strokeLinecap: Maybe[StrokeLinecap] = Absent,
        strokeLinejoin: Maybe[StrokeLinejoin] = Absent,
        strokeDasharray: Maybe[Chunk[Double]] = Absent,
        strokeDashoffset: Maybe[SvgLength] = Absent,
        strokeMiterlimit: Maybe[Double] = Absent,
        opacity: Maybe[Double] = Absent,
        transform: Chunk[Transform] = Chunk.empty,
        // geometry (coords are Double; an explicit SvgLength is stored boxed)
        x: Maybe[Coord] = Absent,
        y: Maybe[Coord] = Absent,
        width: Maybe[Coord] = Absent,
        height: Maybe[Coord] = Absent,
        cx: Maybe[Double] = Absent,
        cy: Maybe[Double] = Absent,
        r: Maybe[Double] = Absent,
        rx: Maybe[Coord] = Absent,
        ry: Maybe[Coord] = Absent,
        x1: Maybe[Double] = Absent,
        y1: Maybe[Double] = Absent,
        x2: Maybe[Double] = Absent,
        y2: Maybe[Double] = Absent,
        fx: Maybe[Double] = Absent,
        fy: Maybe[Double] = Absent,
        points: Maybe[Points] = Absent,
        d: Maybe[PathData] = Absent,
        viewBox: Maybe[ViewBox] = Absent,
        preserveAspectRatio: Maybe[PreserveAspectRatio] = Absent,
        // text
        textAnchor: Maybe[TextAnchor] = Absent,
        dominantBaseline: Maybe[DominantBaseline] = Absent,
        fontSize: Maybe[SvgLength] = Absent,
        fontFamily: Maybe[String] = Absent,
        // references (the typed-handle consumers)
        clipPathRef: Maybe[String] = Absent,
        maskRef: Maybe[String] = Absent,
        filterRef: Maybe[String] = Absent,
        markerStart: Maybe[String] = Absent,
        markerMid: Maybe[String] = Absent,
        markerEnd: Maybe[String] = Absent,
        // units / enum-valued
        gradientUnits: Maybe[Units] = Absent,
        spreadMethod: Maybe[SpreadMethod] = Absent,
        clipPathUnits: Maybe[Units] = Absent,
        maskUnits: Maybe[Units] = Absent,
        patternUnits: Maybe[Units] = Absent,
        markerUnits: Maybe[MarkerUnits] = Absent,
        markerWidth: Maybe[Double] = Absent,
        markerHeight: Maybe[Double] = Absent,
        refX: Maybe[Double] = Absent,
        refY: Maybe[Double] = Absent,
        orient: Maybe[String] = Absent,
        // stop
        offset: Maybe[Double] = Absent,
        stopColor: Maybe[Style.Color] = Absent,
        stopOpacity: Maybe[Double] = Absent,
        // use/textPath reference
        href: Maybe[String] = Absent,
        // the generated id for definition elements (paint-server/clip/mask/marker/filter)
        defId: Maybe[String] = Absent,
        // filter region (the `filter` element's own x/y/width/height/units)
        filterX: Maybe[Coord] = Absent,
        filterY: Maybe[Coord] = Absent,
        filterWidth: Maybe[Coord] = Absent,
        filterHeight: Maybe[Coord] = Absent,
        filterUnits: Maybe[Units] = Absent,
        // filter primitives (fe*)
        stdDeviation: Maybe[Double] = Absent,
        feDx: Maybe[Double] = Absent,
        feDy: Maybe[Double] = Absent,
        feIn: Maybe[String] = Absent,
        feIn2: Maybe[String] = Absent,
        feResult: Maybe[String] = Absent,
        feMode: Maybe[BlendMode] = Absent,
        feColorMatrixType: Maybe[ColorMatrixType] = Absent,
        feValues: Maybe[String] = Absent,
        feFloodColor: Maybe[Style.Color] = Absent,
        feFloodOpacity: Maybe[Double] = Absent,
        feCompositeOperator: Maybe[CompositeOperator] = Absent,
        feMorphologyOperator: Maybe[MorphologyOperator] = Absent,
        feTurbulenceType: Maybe[TurbulenceType] = Absent,
        feBaseFrequency: Maybe[String] = Absent,
        feScale: Maybe[Double] = Absent,
        // SMIL animation
        animAttributeName: Maybe[String] = Absent,
        animFrom: Maybe[String] = Absent,
        animTo: Maybe[String] = Absent,
        animValues: Maybe[String] = Absent,
        animDur: Maybe[String] = Absent,
        animRepeatCount: Maybe[String] = Absent,
        animBegin: Maybe[String] = Absent,
        animCalcMode: Maybe[String] = Absent,
        animKeyTimes: Maybe[String] = Absent,
        animKeySplines: Maybe[String] = Absent,
        animType: Maybe[TransformType] = Absent
    )

    /** A geometry value that is either a plain user-space `Double` or an `SvgLength`. */
    private[kyo] enum Coord derives CanEqual:
        case Num(value: Double)
        case Len(value: SvgLength)

    // ---- Capability traits ----

    /** Setters for the `fill` family. Mixed into shapes, text, and `g`. */
    sealed trait HasFill extends SvgElement:
        def fill(p: Paint): Self         = withSvg(svgAttrs.copy(fill = Present(p)))
        def fillOpacity(v: Double): Self = withSvg(svgAttrs.copy(fillOpacity = Present(v)))
        def fillRule(v: FillRule): Self  = withSvg(svgAttrs.copy(fillRule = Present(v)))
    end HasFill

    /** Setters for the `stroke` family. Mixed into shapes, text, and `g`. */
    sealed trait HasStroke extends SvgElement:
        def stroke(p: Paint): Self                  = withSvg(svgAttrs.copy(stroke = Present(p)))
        def strokeWidth(v: SvgLength): Self         = withSvg(svgAttrs.copy(strokeWidth = Present(v)))
        def strokeWidth(v: Double): Self            = strokeWidth(SvgLength.px(v))
        def strokeOpacity(v: Double): Self          = withSvg(svgAttrs.copy(strokeOpacity = Present(v)))
        def strokeLinecap(v: StrokeLinecap): Self   = withSvg(svgAttrs.copy(strokeLinecap = Present(v)))
        def strokeLinejoin(v: StrokeLinejoin): Self = withSvg(svgAttrs.copy(strokeLinejoin = Present(v)))
        def strokeDasharray(v: Seq[Double]): Self   = withSvg(svgAttrs.copy(strokeDasharray = Present(Chunk.from(v))))
        def strokeDashoffset(v: SvgLength): Self    = withSvg(svgAttrs.copy(strokeDashoffset = Present(v)))
        def strokeMiterlimit(v: Double): Self       = withSvg(svgAttrs.copy(strokeMiterlimit = Present(v)))
    end HasStroke

    /** Setter for `opacity`. */
    sealed trait HasOpacity extends SvgElement:
        def opacity(v: Double): Self = withSvg(svgAttrs.copy(opacity = Present(v)))

    /** Setter for the `transform` list. */
    sealed trait HasTransform extends SvgElement:
        def transform(ts: Transform*): Self = withSvg(svgAttrs.copy(transform = Chunk.from(ts)))

    /** `filter` reference setter (renders `filter="url(#id)"`), mixed into the graphics
      * elements that may carry a filter. Takes a typed `Filter.Ref`, never a raw string.
      */
    sealed trait HasFilter extends SvgElement:
        def filter(ref: Filter.Ref): Self = withSvg(svgAttrs.copy(filterRef = Present(ref.id)))

    /** `x`/`y` geometry setters, with `Double`/`Int`/`SvgLength` overloads. */
    sealed trait Positioned extends SvgElement:
        def x(v: Double): Self    = withSvg(svgAttrs.copy(x = Present(Coord.Num(v))))
        def x(v: Int): Self       = x(v.toDouble)
        def x(v: SvgLength): Self = withSvg(svgAttrs.copy(x = Present(Coord.Len(v))))
        def y(v: Double): Self    = withSvg(svgAttrs.copy(y = Present(Coord.Num(v))))
        def y(v: Int): Self       = y(v.toDouble)
        def y(v: SvgLength): Self = withSvg(svgAttrs.copy(y = Present(Coord.Len(v))))
    end Positioned

    /** `width`/`height` geometry setters, with `Double`/`Int`/`SvgLength` overloads. */
    sealed trait Sized extends SvgElement:
        def width(v: Double): Self     = withSvg(svgAttrs.copy(width = Present(Coord.Num(v))))
        def width(v: Int): Self        = width(v.toDouble)
        def width(v: SvgLength): Self  = withSvg(svgAttrs.copy(width = Present(Coord.Len(v))))
        def height(v: Double): Self    = withSvg(svgAttrs.copy(height = Present(Coord.Num(v))))
        def height(v: Int): Self       = height(v.toDouble)
        def height(v: SvgLength): Self = withSvg(svgAttrs.copy(height = Present(Coord.Len(v))))
    end Sized

    /** Internal alias: the full paint capability set shared by shapes and text. */
    private[kyo] type Paintable = HasFill & HasStroke & HasOpacity

    // ---- Child content models ----
    // Each child type bounds its reactive members to the element family it accepts, so a
    // reactive/foreach/fragment can only inject children that fit the parent's content model.

    type SvgChild       = SvgElement | Reactive[? <: SvgElement] | Foreach[?, ? <: SvgElement] | Fragment[? <: SvgElement]
    type ShapeChildLeaf = Title | Desc | Metadata | Animate | AnimateTransform | AnimateMotion | SetAnim
    type ShapeChild =
        Title | Desc | Metadata | Animate | AnimateTransform | AnimateMotion | SetAnim |
            Reactive[? <: ShapeChildLeaf] | Foreach[?, ? <: ShapeChildLeaf] | Fragment[? <: ShapeChildLeaf]
    // filter content models: `filter` accepts filter primitives; `feMerge` accepts only `feMergeNode`.
    type FilterChild =
        FilterPrimitive | Reactive[? <: FilterPrimitive] | Foreach[?, ? <: FilterPrimitive] | Fragment[? <: FilterPrimitive]
    type MergeNodeChild =
        FeMergeNode | Reactive[? <: FeMergeNode] | Foreach[?, ? <: FeMergeNode] | Fragment[? <: FeMergeNode]
    type TextLeaf = TSpan | TextPath | SvgAnchor
    type TextChild = String | TSpan | TextPath | SvgAnchor |
        Reactive[? <: TextLeaf] | Foreach[?, ? <: TextLeaf] | Fragment[? <: TextLeaf]
    type StopChild = Stop | Reactive[? <: Stop] | Foreach[?, ? <: Stop] | Fragment[? <: Stop]
    // There is no HtmlChild alias: HTML containment is expressed through the AsHtmlChild typeclass.

    private[kyo] def liftSvg(cs: Seq[?]): Chunk[UI] =
        Chunk.from(cs.map {
            case s: String => UI.Ast.Text(s)(using
                    Frame.internal
                ) // internal frame for the framework's string-to-text lift; no user-visible Frame site
            case u: UI => u
        })

    // ---- Element case classes: structure ----

    /** The `<svg>` root: the one SVG type that is also `HtmlContent`, so it embeds
      * directly in any HTML container (`div(Svg.svg(...))`). Acts as an `SvgElement`
      * container on the inside and as an `Inline` `HtmlContent` element on the outside.
      */
    final case class Root(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with UI.Ast.SvgRootNode with Sized with HasTransform:
        type Self = Root
        def withAttrs(a: Attrs): Root                         = copy(attrs = a)
        def withSvg(s: SvgAttrs): Root                        = copy(svgAttrs = s)
        def apply(cs: SvgChild*): Root                        = copy(children = children ++ liftSvg(cs))
        def viewBox(v: ViewBox): Root                         = withSvg(svgAttrs.copy(viewBox = Present(v)))
        def preserveAspectRatio(v: PreserveAspectRatio): Root = withSvg(svgAttrs.copy(preserveAspectRatio = Present(v)))
    end Root

    final case class G(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with HasTransform with UI.Ast.SvgInteractiveNode:
        type Self = G
        def withAttrs(a: Attrs): G         = copy(attrs = a)
        def withSvg(s: SvgAttrs): G        = copy(svgAttrs = s)
        def apply(cs: SvgChild*): G        = copy(children = children ++ liftSvg(cs))
        def clipPath(ref: ClipPath.Ref): G = withSvg(svgAttrs.copy(clipPathRef = Present(ref.id)))
        def mask(ref: Mask.Ref): G         = withSvg(svgAttrs.copy(maskRef = Present(ref.id)))
        def filter(ref: Filter.Ref): G     = withSvg(svgAttrs.copy(filterRef = Present(ref.id)))
    end G

    final case class Defs(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = Defs
        def withAttrs(a: Attrs): Defs  = copy(attrs = a)
        def withSvg(s: SvgAttrs): Defs = copy(svgAttrs = s)
        def apply(cs: SvgChild*): Defs = copy(children = children ++ liftSvg(cs))
    end Defs

    final case class Symbol(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = Symbol
        def withAttrs(a: Attrs): Symbol  = copy(attrs = a)
        def withSvg(s: SvgAttrs): Symbol = copy(svgAttrs = s)
        def apply(cs: SvgChild*): Symbol = copy(children = children ++ liftSvg(cs))
        def viewBox(v: ViewBox): Symbol  = withSvg(svgAttrs.copy(viewBox = Present(v)))

        /** Sets the element's id in `svgAttrs.defId` so `Svg.use(symbol)` resolves it. */
        override def id(v: String): Symbol = withSvg(svgAttrs.copy(defId = Present(v)))
    end Symbol

    final case class Switch(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = Switch
        def withAttrs(a: Attrs): Switch  = copy(attrs = a)
        def withSvg(s: SvgAttrs): Switch = copy(svgAttrs = s)
        def apply(cs: SvgChild*): Switch = copy(children = children ++ liftSvg(cs))
    end Switch

    final case class SvgAnchor(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasTransform with UI.Ast.SvgInteractiveNode:
        type Self = SvgAnchor
        def withAttrs(a: Attrs): SvgAnchor  = copy(attrs = a)
        def withSvg(s: SvgAttrs): SvgAnchor = copy(svgAttrs = s)
        def apply(cs: SvgChild*): SvgAnchor = copy(children = children ++ liftSvg(cs))
        def href(v: UI.Href): SvgAnchor =
            val s = v match
                case UI.Href.Absolute(u)       => u.full
                case UI.Href.Path(p)           => p
                case UI.Href.Fragment(id)      => s"#$id"
                case UI.Href.External(sch, vv) => s"$sch:$vv"
            withSvg(svgAttrs.copy(href = Present(s)))
        end href
    end SvgAnchor

    final case class Use(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with Positioned with Sized with HasTransform:
        type Self = Use
        def withAttrs(a: Attrs): Use  = copy(attrs = a)
        def withSvg(s: SvgAttrs): Use = copy(svgAttrs = s)
    end Use

    // ---- HasId marker for `use(target)` typed reference ----
    /** Capability marker: an element carrying a stable id, usable as a `use` target. */
    sealed trait HasId extends SvgElement:
        def id(v: String): Self

    // ---- shapes ----

    final case class Rect(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with Positioned with Sized with HasTransform with HasFilter
        with UI.Ast.SvgInteractiveNode:
        type Self = Rect
        def withAttrs(a: Attrs): Rect    = copy(attrs = a)
        def withSvg(s: SvgAttrs): Rect   = copy(svgAttrs = s)
        def apply(cs: ShapeChild*): Rect = copy(children = children ++ liftSvg(cs))
        def rx(v: Double): Rect          = withSvg(svgAttrs.copy(rx = Present(Coord.Num(v))))
        def rx(v: Int): Rect             = rx(v.toDouble)
        def ry(v: Double): Rect          = withSvg(svgAttrs.copy(ry = Present(Coord.Num(v))))
        def ry(v: Int): Rect             = ry(v.toDouble)
    end Rect

    final case class Circle(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with HasTransform with HasFilter with UI.Ast.SvgInteractiveNode:
        type Self = Circle
        def withAttrs(a: Attrs): Circle    = copy(attrs = a)
        def withSvg(s: SvgAttrs): Circle   = copy(svgAttrs = s)
        def apply(cs: ShapeChild*): Circle = copy(children = children ++ liftSvg(cs))
        def cx(v: Double): Circle          = withSvg(svgAttrs.copy(cx = Present(v)))
        def cx(v: Int): Circle             = cx(v.toDouble)
        def cy(v: Double): Circle          = withSvg(svgAttrs.copy(cy = Present(v)))
        def cy(v: Int): Circle             = cy(v.toDouble)
        def r(v: Double): Circle           = withSvg(svgAttrs.copy(r = Present(v)))
        def r(v: Int): Circle              = r(v.toDouble)
    end Circle

    final case class Ellipse(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with HasTransform with HasFilter with UI.Ast.SvgInteractiveNode:
        type Self = Ellipse
        def withAttrs(a: Attrs): Ellipse    = copy(attrs = a)
        def withSvg(s: SvgAttrs): Ellipse   = copy(svgAttrs = s)
        def apply(cs: ShapeChild*): Ellipse = copy(children = children ++ liftSvg(cs))
        def cx(v: Double): Ellipse          = withSvg(svgAttrs.copy(cx = Present(v)))
        def cx(v: Int): Ellipse             = cx(v.toDouble)
        def cy(v: Double): Ellipse          = withSvg(svgAttrs.copy(cy = Present(v)))
        def cy(v: Int): Ellipse             = cy(v.toDouble)
        def rx(v: Double): Ellipse          = withSvg(svgAttrs.copy(rx = Present(Coord.Num(v))))
        def rx(v: Int): Ellipse             = rx(v.toDouble)
        def ry(v: Double): Ellipse          = withSvg(svgAttrs.copy(ry = Present(Coord.Num(v))))
        def ry(v: Int): Ellipse             = ry(v.toDouble)
    end Ellipse

    final case class Line(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasStroke with HasOpacity with HasTransform with HasFilter with UI.Ast.SvgInteractiveNode:
        type Self = Line
        def withAttrs(a: Attrs): Line          = copy(attrs = a)
        def withSvg(s: SvgAttrs): Line         = copy(svgAttrs = s)
        def apply(cs: ShapeChild*): Line       = copy(children = children ++ liftSvg(cs))
        def x1(v: Double): Line                = withSvg(svgAttrs.copy(x1 = Present(v)))
        def x1(v: Int): Line                   = x1(v.toDouble)
        def y1(v: Double): Line                = withSvg(svgAttrs.copy(y1 = Present(v)))
        def y1(v: Int): Line                   = y1(v.toDouble)
        def x2(v: Double): Line                = withSvg(svgAttrs.copy(x2 = Present(v)))
        def x2(v: Int): Line                   = x2(v.toDouble)
        def y2(v: Double): Line                = withSvg(svgAttrs.copy(y2 = Present(v)))
        def y2(v: Int): Line                   = y2(v.toDouble)
        def markerStart(ref: Marker.Ref): Line = withSvg(svgAttrs.copy(markerStart = Present(ref.id)))
        def markerMid(ref: Marker.Ref): Line   = withSvg(svgAttrs.copy(markerMid = Present(ref.id)))
        def markerEnd(ref: Marker.Ref): Line   = withSvg(svgAttrs.copy(markerEnd = Present(ref.id)))
    end Line

    final case class Polyline(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with HasTransform with HasFilter with UI.Ast.SvgInteractiveNode:
        type Self = Polyline
        def withAttrs(a: Attrs): Polyline          = copy(attrs = a)
        def withSvg(s: SvgAttrs): Polyline         = copy(svgAttrs = s)
        def apply(cs: ShapeChild*): Polyline       = copy(children = children ++ liftSvg(cs))
        def points(p: Points): Polyline            = withSvg(svgAttrs.copy(points = Present(p)))
        def markerStart(ref: Marker.Ref): Polyline = withSvg(svgAttrs.copy(markerStart = Present(ref.id)))
        def markerMid(ref: Marker.Ref): Polyline   = withSvg(svgAttrs.copy(markerMid = Present(ref.id)))
        def markerEnd(ref: Marker.Ref): Polyline   = withSvg(svgAttrs.copy(markerEnd = Present(ref.id)))
    end Polyline

    final case class Polygon(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with HasTransform with HasFilter with UI.Ast.SvgInteractiveNode:
        type Self = Polygon
        def withAttrs(a: Attrs): Polygon    = copy(attrs = a)
        def withSvg(s: SvgAttrs): Polygon   = copy(svgAttrs = s)
        def apply(cs: ShapeChild*): Polygon = copy(children = children ++ liftSvg(cs))
        def points(p: Points): Polygon      = withSvg(svgAttrs.copy(points = Present(p)))
    end Polygon

    final case class Path(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasId with HasFill with HasStroke with HasOpacity with HasTransform with HasFilter
        with UI.Ast.SvgInteractiveNode:
        type Self = Path
        def withAttrs(a: Attrs): Path          = copy(attrs = a)
        def withSvg(s: SvgAttrs): Path         = copy(svgAttrs = s)
        def apply(cs: ShapeChild*): Path       = copy(children = children ++ liftSvg(cs))
        def d(data: PathData): Path            = withSvg(svgAttrs.copy(d = Present(data)))
        override def id(v: String): Path       = withSvg(svgAttrs.copy(defId = Present(v)))
        def markerStart(ref: Marker.Ref): Path = withSvg(svgAttrs.copy(markerStart = Present(ref.id)))
        def markerMid(ref: Marker.Ref): Path   = withSvg(svgAttrs.copy(markerMid = Present(ref.id)))
        def markerEnd(ref: Marker.Ref): Path   = withSvg(svgAttrs.copy(markerEnd = Present(ref.id)))
    end Path

    // ---- text ----

    final case class Text(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with Positioned with HasTransform with HasFilter
        with UI.Ast.SvgInteractiveNode:
        type Self = Text
        def withAttrs(a: Attrs): Text                   = copy(attrs = a)
        def withSvg(s: SvgAttrs): Text                  = copy(svgAttrs = s)
        def apply(cs: TextChild*): Text                 = copy(children = children ++ liftSvg(cs))
        def textAnchor(v: TextAnchor): Text             = withSvg(svgAttrs.copy(textAnchor = Present(v)))
        def dominantBaseline(v: DominantBaseline): Text = withSvg(svgAttrs.copy(dominantBaseline = Present(v)))
        def fontSize(v: SvgLength): Text                = withSvg(svgAttrs.copy(fontSize = Present(v)))
        def fontFamily(v: String): Text                 = withSvg(svgAttrs.copy(fontFamily = Present(v)))
    end Text

    final case class TSpan(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with Positioned with HasFilter with UI.Ast.SvgInteractiveNode:
        type Self = TSpan
        def withAttrs(a: Attrs): TSpan    = copy(attrs = a)
        def withSvg(s: SvgAttrs): TSpan   = copy(svgAttrs = s)
        def apply(cs: TextChild*): TSpan  = copy(children = children ++ liftSvg(cs))
        def fontSize(v: SvgLength): TSpan = withSvg(svgAttrs.copy(fontSize = Present(v)))
    end TSpan

    final case class TextPath(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with HasFill with HasStroke with HasOpacity with HasFilter with UI.Ast.SvgInteractiveNode:
        type Self = TextPath
        def withAttrs(a: Attrs): TextPath   = copy(attrs = a)
        def withSvg(s: SvgAttrs): TextPath  = copy(svgAttrs = s)
        def apply(cs: TextChild*): TextPath = copy(children = children ++ liftSvg(cs))
    end TextPath

    // ---- gradients and patterns ----

    final case class LinearGradient(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with PaintServer:
        type Self = LinearGradient
        private[kyo] def id: String                       = svgAttrs.defId.getOrElse(genId(frame))
        def withAttrs(a: Attrs): LinearGradient           = copy(attrs = a)
        def withSvg(s: SvgAttrs): LinearGradient          = copy(svgAttrs = s)
        def apply(cs: StopChild*): LinearGradient         = copy(children = children ++ liftSvg(cs))
        def x1(v: Double): LinearGradient                 = withSvg(svgAttrs.copy(x1 = Present(v)))
        def x1(v: Int): LinearGradient                    = x1(v.toDouble)
        def y1(v: Double): LinearGradient                 = withSvg(svgAttrs.copy(y1 = Present(v)))
        def y1(v: Int): LinearGradient                    = y1(v.toDouble)
        def x2(v: Double): LinearGradient                 = withSvg(svgAttrs.copy(x2 = Present(v)))
        def x2(v: Int): LinearGradient                    = x2(v.toDouble)
        def y2(v: Double): LinearGradient                 = withSvg(svgAttrs.copy(y2 = Present(v)))
        def y2(v: Int): LinearGradient                    = y2(v.toDouble)
        def gradientUnits(v: Units): LinearGradient       = withSvg(svgAttrs.copy(gradientUnits = Present(v)))
        def spreadMethod(v: SpreadMethod): LinearGradient = withSvg(svgAttrs.copy(spreadMethod = Present(v)))
        def paint: Paint.Ref                              = Paint.Ref(this.copy(svgAttrs = svgAttrs.copy(defId = Present(id))))
    end LinearGradient

    final case class RadialGradient(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with PaintServer:
        type Self = RadialGradient
        private[kyo] def id: String                       = svgAttrs.defId.getOrElse(genId(frame))
        def withAttrs(a: Attrs): RadialGradient           = copy(attrs = a)
        def withSvg(s: SvgAttrs): RadialGradient          = copy(svgAttrs = s)
        def apply(cs: StopChild*): RadialGradient         = copy(children = children ++ liftSvg(cs))
        def cx(v: Double): RadialGradient                 = withSvg(svgAttrs.copy(cx = Present(v)))
        def cy(v: Double): RadialGradient                 = withSvg(svgAttrs.copy(cy = Present(v)))
        def r(v: Double): RadialGradient                  = withSvg(svgAttrs.copy(r = Present(v)))
        def fx(v: Double): RadialGradient                 = withSvg(svgAttrs.copy(fx = Present(v)))
        def fy(v: Double): RadialGradient                 = withSvg(svgAttrs.copy(fy = Present(v)))
        def gradientUnits(v: Units): RadialGradient       = withSvg(svgAttrs.copy(gradientUnits = Present(v)))
        def spreadMethod(v: SpreadMethod): RadialGradient = withSvg(svgAttrs.copy(spreadMethod = Present(v)))
        def paint: Paint.Ref                              = Paint.Ref(this.copy(svgAttrs = svgAttrs.copy(defId = Present(id))))
    end RadialGradient

    final case class Stop(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = Stop
        def withAttrs(a: Attrs): Stop       = copy(attrs = a)
        def withSvg(s: SvgAttrs): Stop      = copy(svgAttrs = s)
        def offset(v: Double): Stop         = withSvg(svgAttrs.copy(offset = Present(v)))
        def stopColor(v: Style.Color): Stop = withSvg(svgAttrs.copy(stopColor = Present(v)))
        def stopOpacity(v: Double): Stop    = withSvg(svgAttrs.copy(stopOpacity = Present(v)))
    end Stop

    final case class Pattern(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with Positioned with Sized with PaintServer:
        type Self = Pattern
        private[kyo] def id: String         = svgAttrs.defId.getOrElse(genId(frame))
        def withAttrs(a: Attrs): Pattern    = copy(attrs = a)
        def withSvg(s: SvgAttrs): Pattern   = copy(svgAttrs = s)
        def apply(cs: SvgChild*): Pattern   = copy(children = children ++ liftSvg(cs))
        def patternUnits(v: Units): Pattern = withSvg(svgAttrs.copy(patternUnits = Present(v)))
        def viewBox(v: ViewBox): Pattern    = withSvg(svgAttrs.copy(viewBox = Present(v)))
        def paint: Paint.Ref                = Paint.Ref(this.copy(svgAttrs = svgAttrs.copy(defId = Present(id))))
    end Pattern

    // ---- clip and mask ----

    final case class ClipPath(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends DefinitionElement:
        type Self = ClipPath
        private[kyo] def id: String           = svgAttrs.defId.getOrElse(genId(frame))
        def withAttrs(a: Attrs): ClipPath     = copy(attrs = a)
        def withSvg(s: SvgAttrs): ClipPath    = copy(svgAttrs = s)
        def apply(cs: SvgChild*): ClipPath    = copy(children = children ++ liftSvg(cs))
        def clipPathUnits(v: Units): ClipPath = withSvg(svgAttrs.copy(clipPathUnits = Present(v)))
        def clipRef: ClipPath.Ref             = ClipPath.Ref(id)
    end ClipPath
    object ClipPath:
        final case class Ref(id: String)

    final case class Mask(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends DefinitionElement with Sized:
        type Self = Mask
        private[kyo] def id: String    = svgAttrs.defId.getOrElse(genId(frame))
        def withAttrs(a: Attrs): Mask  = copy(attrs = a)
        def withSvg(s: SvgAttrs): Mask = copy(svgAttrs = s)
        def apply(cs: SvgChild*): Mask = copy(children = children ++ liftSvg(cs))
        def maskUnits(v: Units): Mask  = withSvg(svgAttrs.copy(maskUnits = Present(v)))
        def maskRef: Mask.Ref          = Mask.Ref(id)
    end Mask
    object Mask:
        final case class Ref(id: String)

    // ---- image and foreignObject ----

    final case class Image(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with Positioned with Sized with HasTransform with HasFilter with UI.Ast.SvgInteractiveNode:
        type Self = Image
        def withAttrs(a: Attrs): Image                         = copy(attrs = a)
        def withSvg(s: SvgAttrs): Image                        = copy(svgAttrs = s)
        def preserveAspectRatio(v: PreserveAspectRatio): Image = withSvg(svgAttrs.copy(preserveAspectRatio = Present(v)))
    end Image

    final case class ForeignObject(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement with Positioned with Sized with UI.Ast.SvgInteractiveNode:
        type Self = ForeignObject
        def withAttrs(a: Attrs): ForeignObject             = copy(attrs = a)
        def withSvg(s: SvgAttrs): ForeignObject            = copy(svgAttrs = s)
        def apply(cs: UI.Ast.HtmlChildVal*): ForeignObject = copy(children = children ++ liftSvg(cs.map(_.value)))
    end ForeignObject

    // ---- marker ----

    final case class Marker(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends DefinitionElement:
        type Self = Marker
        private[kyo] def id: String             = svgAttrs.defId.getOrElse(genId(frame))
        def withAttrs(a: Attrs): Marker         = copy(attrs = a)
        def withSvg(s: SvgAttrs): Marker        = copy(svgAttrs = s)
        def apply(cs: SvgChild*): Marker        = copy(children = children ++ liftSvg(cs))
        def markerWidth(v: Double): Marker      = withSvg(svgAttrs.copy(markerWidth = Present(v)))
        def markerHeight(v: Double): Marker     = withSvg(svgAttrs.copy(markerHeight = Present(v)))
        def refX(v: Double): Marker             = withSvg(svgAttrs.copy(refX = Present(v)))
        def refY(v: Double): Marker             = withSvg(svgAttrs.copy(refY = Present(v)))
        def markerUnits(v: MarkerUnits): Marker = withSvg(svgAttrs.copy(markerUnits = Present(v)))
        def orient(v: String): Marker           = withSvg(svgAttrs.copy(orient = Present(v)))
        def viewBox(v: ViewBox): Marker         = withSvg(svgAttrs.copy(viewBox = Present(v)))
        def markerRef: Marker.Ref               = Marker.Ref(id)
    end Marker
    object Marker:
        final case class Ref(id: String)

    // ---- filters ----

    /** A `<filter>` container: defines a filter region and holds the filter-primitive
      * pipeline. Consumers reference it through the typed `Filter.Ref` produced by
      * `filterRef` (rendered as `filter="url(#id)"`), never a raw `url(#id)` string.
      */
    final case class Filter(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends DefinitionElement:
        type Self = Filter
        private[kyo] def id: String         = svgAttrs.defId.getOrElse(genId(frame))
        def withAttrs(a: Attrs): Filter     = copy(attrs = a)
        def withSvg(s: SvgAttrs): Filter    = copy(svgAttrs = s)
        def apply(cs: FilterChild*): Filter = copy(children = children ++ liftSvg(cs))
        def x(v: Double): Filter            = withSvg(svgAttrs.copy(filterX = Present(Coord.Num(v))))
        def x(v: SvgLength): Filter         = withSvg(svgAttrs.copy(filterX = Present(Coord.Len(v))))
        def y(v: Double): Filter            = withSvg(svgAttrs.copy(filterY = Present(Coord.Num(v))))
        def y(v: SvgLength): Filter         = withSvg(svgAttrs.copy(filterY = Present(Coord.Len(v))))
        def width(v: Double): Filter        = withSvg(svgAttrs.copy(filterWidth = Present(Coord.Num(v))))
        def width(v: SvgLength): Filter     = withSvg(svgAttrs.copy(filterWidth = Present(Coord.Len(v))))
        def height(v: Double): Filter       = withSvg(svgAttrs.copy(filterHeight = Present(Coord.Num(v))))
        def height(v: SvgLength): Filter    = withSvg(svgAttrs.copy(filterHeight = Present(Coord.Len(v))))
        def filterUnits(v: Units): Filter   = withSvg(svgAttrs.copy(filterUnits = Present(v)))
        def filterRef: Filter.Ref           = Filter.Ref(id)
    end Filter
    object Filter:
        final case class Ref(id: String)

    final case class FeGaussianBlur(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeGaussianBlur
        def withAttrs(a: Attrs): FeGaussianBlur     = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeGaussianBlur    = copy(svgAttrs = s)
        def stdDeviation(v: Double): FeGaussianBlur = withSvg(svgAttrs.copy(stdDeviation = Present(v)))
        def in(v: String): FeGaussianBlur           = withSvg(svgAttrs.copy(feIn = Present(v)))
        def result(v: String): FeGaussianBlur       = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeGaussianBlur

    final case class FeOffset(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeOffset
        def withAttrs(a: Attrs): FeOffset  = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeOffset = copy(svgAttrs = s)
        def dx(v: Double): FeOffset        = withSvg(svgAttrs.copy(feDx = Present(v)))
        def dx(v: Int): FeOffset           = dx(v.toDouble)
        def dy(v: Double): FeOffset        = withSvg(svgAttrs.copy(feDy = Present(v)))
        def dy(v: Int): FeOffset           = dy(v.toDouble)
        def in(v: String): FeOffset        = withSvg(svgAttrs.copy(feIn = Present(v)))
        def result(v: String): FeOffset    = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeOffset

    final case class FeBlend(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeBlend
        def withAttrs(a: Attrs): FeBlend  = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeBlend = copy(svgAttrs = s)
        def mode(v: BlendMode): FeBlend   = withSvg(svgAttrs.copy(feMode = Present(v)))
        def in(v: String): FeBlend        = withSvg(svgAttrs.copy(feIn = Present(v)))
        def in2(v: String): FeBlend       = withSvg(svgAttrs.copy(feIn2 = Present(v)))
        def result(v: String): FeBlend    = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeBlend

    final case class FeColorMatrix(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeColorMatrix
        def withAttrs(a: Attrs): FeColorMatrix        = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeColorMatrix       = copy(svgAttrs = s)
        def `type`(v: ColorMatrixType): FeColorMatrix = withSvg(svgAttrs.copy(feColorMatrixType = Present(v)))
        def values(v: String): FeColorMatrix          = withSvg(svgAttrs.copy(feValues = Present(v)))
        def in(v: String): FeColorMatrix              = withSvg(svgAttrs.copy(feIn = Present(v)))
        def result(v: String): FeColorMatrix          = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeColorMatrix

    final case class FeFlood(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeFlood
        def withAttrs(a: Attrs): FeFlood        = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeFlood       = copy(svgAttrs = s)
        def floodColor(v: Style.Color): FeFlood = withSvg(svgAttrs.copy(feFloodColor = Present(v)))
        def floodOpacity(v: Double): FeFlood    = withSvg(svgAttrs.copy(feFloodOpacity = Present(v)))
        def result(v: String): FeFlood          = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeFlood

    final case class FeComposite(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeComposite
        def withAttrs(a: Attrs): FeComposite            = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeComposite           = copy(svgAttrs = s)
        def operator(v: CompositeOperator): FeComposite = withSvg(svgAttrs.copy(feCompositeOperator = Present(v)))
        def in(v: String): FeComposite                  = withSvg(svgAttrs.copy(feIn = Present(v)))
        def in2(v: String): FeComposite                 = withSvg(svgAttrs.copy(feIn2 = Present(v)))
        def result(v: String): FeComposite              = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeComposite

    /** `<feMerge>`: a filter primitive whose only valid children are `feMergeNode`
      * elements (enforced by the `MergeNodeChild` content model).
      */
    final case class FeMerge(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeMerge
        def withAttrs(a: Attrs): FeMerge        = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeMerge       = copy(svgAttrs = s)
        def apply(cs: MergeNodeChild*): FeMerge = copy(children = children ++ liftSvg(cs))
        def result(v: String): FeMerge          = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeMerge

    /** `<feMergeNode>`: extends `SvgElement` (NOT `FilterPrimitive`) so it can only be
      * placed inside `feMerge` via `MergeNodeChild`, never directly in `filter`.
      */
    final case class FeMergeNode(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = FeMergeNode
        def withAttrs(a: Attrs): FeMergeNode  = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeMergeNode = copy(svgAttrs = s)
        def in(v: String): FeMergeNode        = withSvg(svgAttrs.copy(feIn = Present(v)))
    end FeMergeNode

    final case class FeImage(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeImage
        def withAttrs(a: Attrs): FeImage  = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeImage = copy(svgAttrs = s)
        def href(v: String): FeImage      = withSvg(svgAttrs.copy(href = Present(v)))
        def result(v: String): FeImage    = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeImage

    final case class FeTile(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeTile
        def withAttrs(a: Attrs): FeTile  = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeTile = copy(svgAttrs = s)
        def in(v: String): FeTile        = withSvg(svgAttrs.copy(feIn = Present(v)))
        def result(v: String): FeTile    = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeTile

    final case class FeMorphology(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeMorphology
        def withAttrs(a: Attrs): FeMorphology             = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeMorphology            = copy(svgAttrs = s)
        def operator(v: MorphologyOperator): FeMorphology = withSvg(svgAttrs.copy(feMorphologyOperator = Present(v)))
        def in(v: String): FeMorphology                   = withSvg(svgAttrs.copy(feIn = Present(v)))
        def result(v: String): FeMorphology               = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeMorphology

    final case class FeTurbulence(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeTurbulence
        def withAttrs(a: Attrs): FeTurbulence       = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeTurbulence      = copy(svgAttrs = s)
        def baseFrequency(v: String): FeTurbulence  = withSvg(svgAttrs.copy(feBaseFrequency = Present(v)))
        def `type`(v: TurbulenceType): FeTurbulence = withSvg(svgAttrs.copy(feTurbulenceType = Present(v)))
        def result(v: String): FeTurbulence         = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeTurbulence

    final case class FeDisplacementMap(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends FilterPrimitive:
        type Self = FeDisplacementMap
        def withAttrs(a: Attrs): FeDisplacementMap  = copy(attrs = a)
        def withSvg(s: SvgAttrs): FeDisplacementMap = copy(svgAttrs = s)
        def scale(v: Double): FeDisplacementMap     = withSvg(svgAttrs.copy(feScale = Present(v)))
        def in(v: String): FeDisplacementMap        = withSvg(svgAttrs.copy(feIn = Present(v)))
        def in2(v: String): FeDisplacementMap       = withSvg(svgAttrs.copy(feIn2 = Present(v)))
        def result(v: String): FeDisplacementMap    = withSvg(svgAttrs.copy(feResult = Present(v)))
    end FeDisplacementMap

    // ---- SMIL animation: leaf elements placed inside a shape via ShapeChild ----

    /** `<animate>`: animates a single attribute over time. Numeric `from`/`to` overloads
      * format with the shared `NumberFormat.double` encoder (so `from(20.0)` is `"20"`).
      */
    final case class Animate(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = Animate
        def withAttrs(a: Attrs): Animate      = copy(attrs = a)
        def withSvg(s: SvgAttrs): Animate     = copy(svgAttrs = s)
        def attributeName(v: String): Animate = withSvg(svgAttrs.copy(animAttributeName = Present(v)))
        def from(v: Double): Animate          = withSvg(svgAttrs.copy(animFrom = Present(NumberFormat.double(v))))
        def from(v: String): Animate          = withSvg(svgAttrs.copy(animFrom = Present(v)))
        def to(v: Double): Animate            = withSvg(svgAttrs.copy(animTo = Present(NumberFormat.double(v))))
        def to(v: String): Animate            = withSvg(svgAttrs.copy(animTo = Present(v)))
        def values(v: String): Animate        = withSvg(svgAttrs.copy(animValues = Present(v)))
        def dur(v: String): Animate           = withSvg(svgAttrs.copy(animDur = Present(v)))
        def repeatCount(v: String): Animate   = withSvg(svgAttrs.copy(animRepeatCount = Present(v)))
        def begin(v: String): Animate         = withSvg(svgAttrs.copy(animBegin = Present(v)))

        /** Sets the interpolation mode between keyframe values. `CalcMode.Spline` is the mode that activates
          * `keySplines`; the other modes ignore it.
          */
        def calcMode(v: CalcMode): Animate = withSvg(svgAttrs.copy(animCalcMode = Present(calcModeToken(v))))

        /** Sets the `keyTimes` list (semicolon-separated fractions in `[0,1]`) defining when each keyframe value
          * applies.
          */
        def keyTimes(v: String): Animate = withSvg(svgAttrs.copy(animKeyTimes = Present(v)))

        /** Sets the `keySplines` control points for spline interpolation. Requires `calcMode = CalcMode.Spline` to
          * take effect.
          */
        def keySplines(v: String): Animate = withSvg(svgAttrs.copy(animKeySplines = Present(v)))
    end Animate

    /** `<animateTransform>`: animates a transform attribute on the parent element over time.
      * The `type` setter selects the transform kind (`Translate`, `Scale`, `Rotate`,
      * `SkewX`, or `SkewY`). The `from`/`to` values are tuples formatted as a
      * space-separated string per the SVG spec (e.g., `"0 0"` for translate).
      * Use `attributeName` to specify which transform attribute to animate (typically
      * `"transform"`). Timing is controlled by `dur`, `begin`, and `repeatCount`.
      */
    final case class AnimateTransform(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = AnimateTransform
        def withAttrs(a: Attrs): AnimateTransform      = copy(attrs = a)
        def withSvg(s: SvgAttrs): AnimateTransform     = copy(svgAttrs = s)
        def attributeName(v: String): AnimateTransform = withSvg(svgAttrs.copy(animAttributeName = Present(v)))
        def `type`(v: TransformType): AnimateTransform = withSvg(svgAttrs.copy(animType = Present(v)))
        def from(v: String): AnimateTransform          = withSvg(svgAttrs.copy(animFrom = Present(v)))
        def to(v: String): AnimateTransform            = withSvg(svgAttrs.copy(animTo = Present(v)))
        def dur(v: String): AnimateTransform           = withSvg(svgAttrs.copy(animDur = Present(v)))
        def repeatCount(v: String): AnimateTransform   = withSvg(svgAttrs.copy(animRepeatCount = Present(v)))
        def begin(v: String): AnimateTransform         = withSvg(svgAttrs.copy(animBegin = Present(v)))
    end AnimateTransform

    /** `<animateMotion>`: moves the parent element along a motion path over time.
      * The `path` setter accepts a `PathData` value (the same `d` attribute used by
      * `<path>`) describing the trajectory. Timing is controlled by `dur` and
      * `repeatCount`. Unlike `Animate`, this element does not take `attributeName`
      * because it always targets the implicit motion path transform.
      */
    final case class AnimateMotion(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = AnimateMotion
        def withAttrs(a: Attrs): AnimateMotion    = copy(attrs = a)
        def withSvg(s: SvgAttrs): AnimateMotion   = copy(svgAttrs = s)
        def path(v: PathData): AnimateMotion      = withSvg(svgAttrs.copy(d = Present(v)))
        def dur(v: String): AnimateMotion         = withSvg(svgAttrs.copy(animDur = Present(v)))
        def repeatCount(v: String): AnimateMotion = withSvg(svgAttrs.copy(animRepeatCount = Present(v)))
    end AnimateMotion

    /** `<set>`: sets an attribute to a value at a time. Named `SetAnim` to avoid clashing
      * with `scala.collection.Set`; the factory is `Svg.set`.
      */
    final case class SetAnim(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = SetAnim
        def withAttrs(a: Attrs): SetAnim      = copy(attrs = a)
        def withSvg(s: SvgAttrs): SetAnim     = copy(svgAttrs = s)
        def attributeName(v: String): SetAnim = withSvg(svgAttrs.copy(animAttributeName = Present(v)))
        def to(v: String): SetAnim            = withSvg(svgAttrs.copy(animTo = Present(v)))
        def begin(v: String): SetAnim         = withSvg(svgAttrs.copy(animBegin = Present(v)))
    end SetAnim

    // ---- metadata ----

    final case class Title(text: String, svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = Title
        def withAttrs(a: Attrs): Title  = copy(attrs = a)
        def withSvg(s: SvgAttrs): Title = copy(svgAttrs = s)
    end Title

    final case class Desc(text: String, svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = Desc
        def withAttrs(a: Attrs): Desc  = copy(attrs = a)
        def withSvg(s: SvgAttrs): Desc = copy(svgAttrs = s)
    end Desc

    final case class Metadata(svgAttrs: SvgAttrs = SvgAttrs(), attrs: Attrs = Attrs(), children: Chunk[UI] = Chunk.empty)(using
        val frame: Frame
    ) extends SvgElement:
        type Self = Metadata
        def withAttrs(a: Attrs): Metadata  = copy(attrs = a)
        def withSvg(s: SvgAttrs): Metadata = copy(svgAttrs = s)
        def apply(cs: SvgChild*): Metadata = copy(children = children ++ liftSvg(cs))
    end Metadata

    // ---- deterministic id source ----
    /** Generate a deterministic id from a `Frame`, stable across all three render
      * targets (no global counter, no randomness).
      */
    private[kyo] def genId(frame: Frame): String = s"kyo-${Integer.toHexString(frame.hashCode)}"

end Svg
