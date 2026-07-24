package kyo

import kyo.internal.Bundle

/** A resolved translation handle: parsed bundles plus the reactive current locale.
  *
  * An `I18n` is built once by [[I18n.init]] (or [[I18n.initInMemory]]) and provided ambiently for the scope of
  * an application via [[I18n.let]]. Application code does not pass it around: the module-level [[I18n.t]],
  * [[I18n.now]], [[I18n.setLocale]] and the `i18n"..."` interpolator read the ambient handle, so a translated
  * value reads exactly like a plain call with no dependency threaded through every signature.
  *
  * The handle is held in a `Local`, whose default is an empty handle that renders every key as its miss marker
  * `‹key›`. Reading a translation before `init` therefore produces a visible marker rather than throwing.
  *
  * @see
  *   [[I18n.t]] for the reactive translation leaf, [[I18n.now]] for a point-in-time lookup
  * @see
  *   [[I18n.init]], [[I18n.initInMemory]] for building a handle, [[I18n.let]] for providing it
  * @see
  *   [[Locale]] for the locale identifier the handle is keyed on
  */
final class I18n private[kyo] (
    private[kyo] val bundles: Map[Locale, Bundle],
    val locale: Signal[Locale],
    private val setLocaleFn: Locale => Unit < Sync
):

    /** The active locale, read once (does not subscribe). Point-in-time counterpart to [[locale]]. */
    def currentLocale(using Frame): Locale < Sync = locale.current

    /** Sets the active locale, emitting on [[locale]] so every reactive leaf re-renders. */
    def setLocale(target: Locale)(using Frame): Unit < Sync = setLocaleFn(target)

    private[kyo] def lookup(loc: Locale, key: String, args: I18n.Args): String =
        bundles.get(loc).fold(Absent: Maybe[kyo.internal.Message])(_.get(key)) match
            case Present(message) => message.render(I18n.coerce(args))
            case Absent           => s"${I18n.MissOpen}$key${I18n.MissClose}"

    private[kyo] def leaf(key: String, args: I18n.Args)(using Frame): Signal[String] =
        val sigArgs = args.iterator.collect { case (name, sig: Signal[String] @unchecked) => name -> sig }.toList
        if sigArgs.isEmpty then locale.map(loc => lookup(loc, key, args))
        else
            val inputs: Seq[Signal[I18n.Sample]] =
                locale.map(I18n.Sample.Loc(_)) +: sigArgs.map((_, sig) => sig.map(I18n.Sample.Str(_)))
            Signal.combineLatestAll(inputs).map { samples =>
                val loc = samples.head match
                    case I18n.Sample.Loc(l) => l
                    case I18n.Sample.Str(_) => Locale("und")
                val values   = samples.tail.iterator.collect { case I18n.Sample.Str(v) => v }.toSeq
                val resolved = args ++ sigArgs.iterator.map(_._1).zip(values)
                lookup(loc, key, resolved)
            }
        end if
    end leaf
end I18n

/** Internationalization facade over pure-Scala Fluent-subset bundles.
  *
  * Translations are parsed once into per-locale bundles and formatted synchronously; there is no dependency on
  * a JavaScript Fluent runtime, so the module is cross-platform and adds nothing beyond `kyo-core`. The active
  * locale is a `Signal`, so [[t]] can hand back a bare `Signal[String]` that a UI layer lifts into a reactive
  * text node: switching the locale re-renders exactly the affected leaves.
  *
  * The handle built by [[init]] is provided ambiently through [[let]] and read back inside [[t]] at sample
  * time, so call sites carry no `using` parameter and no pending effect. A key that is missing (or defined but
  * empty) renders as `‹key›`, never as a silent fallback to another locale.
  *
  * @see
  *   [[t]], [[now]], [[at]] for translation, [[setLocale]] for switching
  * @see
  *   [[init]], [[initInMemory]] for building a handle, [[let]] for providing it
  * @see
  *   [[Locale]] for the locale identifier, [[I18n.Arg]] for interpolation argument types
  */
object I18n:

    /** An interpolation argument. A `Signal[String]` argument (typically a nested [[t]]) keeps the composed
      * message reactive; other values are formatted once at build time.
      */
    type Arg = String | Int | Long | Double | Boolean | Signal[String]

    /** Named interpolation arguments for a message. */
    type Args = Map[String, Arg]

    private[kyo] enum Sample derives CanEqual:
        case Loc(locale: Locale)
        case Str(value: String)

    private[kyo] val MissOpen  = '‹'
    private[kyo] val MissClose = '›'

    private val i18nLocal: Local[I18n] =
        Local.init(new I18n(Map.empty, Signal.initConst(Locale("und"))(using Frame.internal), _ => ()))

    /** The reactive translation leaf, and the default way to translate. Looks up `key` per locale emission,
      * interpolating `{ $name }` placeholders from `args`; a `Signal[String]` argument keeps the result
      * reactive to that argument too. A missing or empty key renders as `‹key›`.
      */
    def t(key: String, args: Args = Map.empty)(using Frame): Signal[String] =
        Signal.initRaw[String](
            currentWith = [B, S] => (f: String => B < S) => i18nLocal.use(_.leaf(key, args).currentWith(f)),
            nextWith = [B, S] => (f: String => B < S) => i18nLocal.use(_.leaf(key, args).nextWith(f))
        )

    /** Point-in-time lookup against the active locale, for values that must not re-translate after the fact
      * (toast messages, exception messages, log lines).
      */
    def now(key: String, args: Args = Map.empty)(using Frame): String < Sync =
        i18nLocal.use(handle => handle.currentLocale.map(loc => handle.lookup(loc, key, args)))

    /** Lookup at an explicit locale, ignoring the active one. The building block for hand-composed strings. */
    def at(locale: Locale, key: String, args: Args = Map.empty)(using Frame): String < Sync =
        i18nLocal.use(handle => (handle.lookup(locale, key, args): String < Sync))

    /** Sets the active locale on the ambient handle, re-rendering every reactive leaf. */
    def setLocale(locale: Locale)(using Frame): Unit < Sync =
        i18nLocal.use(_.setLocale(locale))

    /** The active locale of the ambient handle, read once (does not subscribe). */
    def currentLocale(using Frame): Locale < Sync =
        i18nLocal.use(_.currentLocale)

    /** Provides `handle` as the ambient translation source for `v`. Every [[t]] leaf built inside (whenever it
      * is sampled) resolves against it; leaves sampled outside any `let` render miss markers.
      */
    def let[A, S](handle: I18n)(v: A < S)(using Frame): A < S =
        i18nLocal.let(handle)(v)

    /** Builds a handle from in-memory bundle text, keyed by locale, starting at `start`. The fetch-free
      * counterpart to [[init]], for tests and for callers that already hold the bundle sources.
      */
    def initInMemory(bundles: Map[Locale, String], start: Locale)(using Frame): I18n < Sync =
        Signal.initRef(start).map { ref =>
            val parsed = bundles.view.mapValues(Bundle.parse).toMap
            new I18n(parsed, ref, loc => ref.set(loc))
        }

    /** Builds a handle by loading each locale's bundle text via `load`, starting at `start`. The `load`
      * function supplies the platform-specific source (an HTTP fetch, a classpath resource); its effects flow
      * into the result.
      */
    def init[S](locales: Seq[Locale], start: Locale)(load: Locale => String < (Async & S))(using Frame): I18n < (Async & S) =
        Kyo.foreach(locales)(loc => load(loc).map(loc -> _)).map { pairs =>
            val parsed = pairs.iterator.map((loc, ftl) => loc -> Bundle.parse(ftl)).toMap
            Signal.initRef(start).map(ref => new I18n(parsed, ref, loc => ref.set(loc)))
        }

    private[kyo] def coerce(args: Args): Map[String, String] =
        args.iterator.collect {
            case (name, v: String)  => name -> v
            case (name, v: Int)     => name -> v.toString
            case (name, v: Long)    => name -> v.toString
            case (name, v: Double)  => name -> v.toString
            case (name, v: Boolean) => name -> v.toString
        }.toMap

    private[kyo] def interpolate(sc: StringContext, args: Seq[Arg])(using Frame): Signal[String] =
        val sigs: Seq[Signal[String]] = args.collect { case sig: Signal[String] @unchecked => sig }.toSeq
        def render(resolved: Iterator[String]): String =
            val sb = new StringBuilder(StringContext.processEscapes(sc.parts.head))
            args.iterator.zip(sc.parts.tail.iterator).foreach { (arg, part) =>
                val value = arg match
                    case _: Signal[String] @unchecked => resolved.next()
                    case other                        => other.toString
                sb.append(value).append(StringContext.processEscapes(part))
            }
            sb.toString
        end render
        if sigs.isEmpty then
            Signal.initRaw[String](
                currentWith = [B, S] => (f: String => B < S) => i18nLocal.use(_.locale.currentWith(_ => f(render(Iterator.empty)))),
                nextWith = [B, S] => (f: String => B < S) => i18nLocal.use(_.locale.nextWith(_ => f(render(Iterator.empty))))
            )
        else Signal.combineLatestAll(sigs).map(chunk => render(chunk.iterator))
        end if
    end interpolate
end I18n

/** The `i18n"..."` interpolator: composes literal text and arguments into one reactive `Signal[String]`. A
  * `Signal[String]` argument (typically a [[I18n.t]] leaf) re-renders the composed string on every emission;
  * other arguments are formatted once. With no signal argument the result still re-derives on locale change.
  */
extension (sc: StringContext)
    def i18n(args: I18n.Arg*)(using Frame): Signal[String] = I18n.interpolate(sc, args)
