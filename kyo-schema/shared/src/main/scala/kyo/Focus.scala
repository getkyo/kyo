package kyo

import scala.language.dynamics

/** A pure lens from a root type to a focused value type, parameterized by an access mode.
  *
  * Focus provides get/set/modify operations on a single navigation path, along with field metadata access and Maybe mode-specific
  * convenience methods. For field-level validation, use `Schema.check(_.field)(pred, msg)` and `Schema.validate(value)`.
  *
  * Mode[_] forms a lattice that grows as navigation crosses more complex structural boundaries:
  *   - `Focus.Id` — product (case class) field access; the value is always present
  *   - `Maybe` — sum-type variant access; the value may be absent when the active variant differs
  *   - `Chunk` — collection element access; zero or more elements
  *
  * Composing foci follows the lattice rules: Id composed with Id stays Id, Id composed with Maybe becomes Maybe, and any composition
  * involving Chunk yields Chunk. This makes the access mode a compile-time indicator of how many values (and how certain) you will get.
  *
  * Obtain a Focus via `Schema[A].focus(_.field)` for product fields, `Schema[A].focus(_.Variant.field)` for sum fields, or
  * `Schema[A].foreach(_.collection)` for collection elements.
  *
  * @tparam Root
  *   The root type this Focus navigates from
  * @tparam Value
  *   The focused value type at the current navigation point
  * @tparam Mode
  *   The access mode type constructor (Id, Maybe, or Chunk)
  *
  * @see
  *   [[Schema.focus]] for the primary entry point on product and sum types
  * @see
  *   [[Schema.foreach]] for collection traversal
  * @see
  *   [[Schema.check]] for field-level validation
  */
final class Focus[Root, Value, Mode[_]] private[kyo] (
    val path: Seq[String],
    private[kyo] val getter: Root => Mode[Value],
    private[kyo] val setter: (Root, Mode[Value]) => Root,
    private[kyo] val updateFn: (Root, Value => Value) => Root,
    private[kyo] val schema: Schema[Root]
):

    /** Gets the focused value from the root.
      *
      * @param root
      *   the root value
      * @return
      *   the focused value wrapped in the Mode type constructor
      */
    def get(root: Root): Mode[Value] = getter(root)

    /** Sets the focused value in the root, returning a new root.
      *
      * For immediate, single-field updates on a value you already have, use this method. For batching multiple field mutations into a
      * reusable unit, use [[Modify]] instead.
      *
      * @param root
      *   the root value
      * @param value
      *   the new focused value
      * @return
      *   a new root with the focused value replaced
      * @see
      *   [[Modify.set]] for batched, multi-field mutations
      */
    def set(root: Root, value: Mode[Value]): Root = setter(root, value)

    /** Updates the focused value using a function, returning a new root.
      *
      * For Id mode, applies f to the single value. For Maybe mode, no-op if absent. For Chunk mode, maps f over all elements.
      *
      * For immediate, single-field transforms on a value you already have, use this method. For batching multiple field mutations into a
      * reusable unit, use [[Modify]] instead.
      *
      * @param root
      *   the root value
      * @param f
      *   transformation function for the focused value
      * @return
      *   a new root with the focused value transformed
      * @see
      *   [[Modify.update]] for batched, multi-field mutations
      */
    def update(root: Root)(f: Value => Value): Root = updateFn(root, f)

    /** Returns the type tag for the focused value type. Summoned at compile time. */
    def tag(using t: Tag[Value]): Tag[Value] = t

    /** Returns the documentation for this focused field, or Maybe.empty if none set. */
    def doc: Maybe[String] =
        if path.nonEmpty then
            schema.fieldDocs.get(path) match
                case Some(d) => Maybe(d)
                case None    => Maybe.empty
        else Maybe.empty

    /** Returns the deprecation reason for this focused field, or Maybe.empty if not deprecated. */
    def deprecated: Maybe[String] =
        if path.nonEmpty then
            schema.fieldDeprecated.get(path) match
                case Some(r) => Maybe(r)
                case None    => Maybe.empty
        else Maybe.empty

    /** Returns the default value for this focused field, or Maybe.empty if none. */
    def default: Maybe[Value] =
        if path.nonEmpty then
            val name = path.last
            Maybe.fromOption(schema.sourceFields.find(_.name == name))
                .flatMap(f => f.default.asInstanceOf[Maybe[Value]])
        else Maybe.empty

    /** Returns whether this focused field is optional (Maybe/Option typed). */
    def optional: Boolean =
        path.nonEmpty && {
            val name = path.last
            schema.sourceFields.exists { sf =>
                sf.name == name && (sf.tag <:< Tag[Option[Any]] || sf.tag <:< Tag[Maybe[Any]])
            }
        }

    /** Returns the stable field ID for this focused field. */
    def fieldId: Int =
        if path.nonEmpty then
            schema.fieldIdOverrides.getOrElse(path, kyo.internal.CodecMacro.fieldId(path.last))
        else 0

    /** Returns constraints targeting this focused field path. */
    def constraints: Chunk[Schema.Constraint] =
        Chunk.from(schema.constraints.filter(_.segments == path))

    /** Navigates into a field of the focused value, composing with the outer mode.
      *
      * Composition follows the Mode[_] lattice: Id < Maybe < Chunk.
      *   - Id.focus(product) => Id, Id.focus(sum) => Maybe
      *   - Maybe.focus(anything) => Maybe
      *   - Chunk.focus(anything) => Chunk
      */
    transparent inline def focus[Value2](inline f: Focus.Select[Value, Value] => Focus.Select[Value, Value2]): Any =
        ${ internal.FocusMacro.focusChainImpl[Root, Value, Mode, Value2]('this, 'f) }

    /** Navigates into a collection field of the focused value, always producing Chunk. */
    inline def foreach[C <: Seq[E], E](inline f: Focus.Select[Value, Value] => Focus.Select[Value, C]): Focus[Root, E, Chunk] =
        ${ internal.FocusMacro.foreachChainImpl[Root, Value, Mode, C, E]('this, 'f) }

    // --- Maybe mode methods ---

    /** Returns the focused value, or `default` if the variant does not match.
      *
      * Only available on Maybe-mode foci (created via sum-type variant navigation).
      *
      * @param root
      *   the root value
      * @param default
      *   the value to return when the focused variant is absent
      * @return
      *   the focused value when present, or `default` otherwise
      */
    def getOrElse(root: Root)(default: => Value)(using Focus.IsMaybe[Mode]): Value =
        getter(root).asInstanceOf[Maybe[Value]] match
            case Maybe.Present(v) => v
            case _                => default

    /** Returns true if the focused variant is present in the root.
      *
      * Only available on Maybe-mode foci (created via sum-type variant navigation).
      *
      * @param root
      *   the root value
      * @return
      *   true if the root's active variant matches this focus, false otherwise
      */
    def isDefined(root: Root)(using Focus.IsMaybe[Mode]): Boolean =
        getter(root).asInstanceOf[Maybe[Value]] match
            case Maybe.Present(_) => true
            case _                => false

end Focus

object Focus:

    /** Identity type constructor for product field access. */
    type Id[X] = X

    /** Evidence that a Focus operates in Maybe mode (sum variant navigation).
      *
      * Maybe-mode methods like `getOrElse` and `isDefined` require this evidence. It is automatically available for Focus instances created
      * via sum-type variant navigation.
      */
    @scala.annotation.implicitNotFound(
        "This method requires a Maybe-mode Focus (from sum variant navigation). " +
            "This Focus has ${M} mode."
    )
    sealed abstract class IsMaybe[M[_]]
    object IsMaybe:
        given IsMaybe[Maybe] with {}

    /** Lambda navigator used in focus lambdas for structural type navigation.
      *
      * Select[A, F] is a Dynamic that resolves field access by name at compile time via FocusMacro.focusSelectImpl. Each selectDynamic call
      * composes getter/setter/segments, building up a navigation chain from root type A to some target value type.
      *
      * @tparam A
      *   The root type
      * @tparam F
      *   The current structural type being navigated
      */
    final class Select[A, F] private[kyo] (
        private[kyo] val getter: A => Maybe[F],
        private[kyo] val setter: (A, F) => A,
        private[kyo] val segments: Seq[String],
        private[kyo] val isPartial: Boolean,
        private[kyo] val schema: Maybe[Any] = Maybe.empty
    ) extends Dynamic:

        /** Navigates to a named field or variant. Returns Select[A, V] where V is the resolved field/variant type. */
        transparent inline def selectDynamic[Name <: String & Singleton](name: Name): Any =
            ${ internal.FocusMacro.focusSelectImpl[A, F, Name]('this, 'name) }

    end Select

    object Select:
        /** Internal factory for creating Select instances from inline/macro contexts. */
        private[kyo] def create[A, F](
            getter: A => Maybe[F],
            setter: (A, F) => A,
            segments: Seq[String],
            isPartial: Boolean = false,
            schema: Maybe[Any] = Maybe.empty
        ): Select[A, F] = new Select[A, F](getter, setter, segments, isPartial, schema)

        /** Creates an identity Select navigator for type T. Used internally by Compare, Modify, and Focus to start focus lambda navigation
          * from the root type.
          */
        private[kyo] def apply[T]: Select[T, T] =
            create[T, T]((r: T) => Maybe(r), (_: T, v: T) => v, Seq.empty)
    end Select

    /** Creates a Focus for direct product field access (always present). */
    private[kyo] def createId[Root, Value](
        getter: Root => Value,
        setter: (Root, Value) => Root,
        path: Seq[String],
        schema: Schema[Root]
    ): Focus[Root, Value, Id] =
        new Focus[Root, Value, Id](
            path,
            root => getter(root),
            (root, v) => setter(root, v),
            (root, f) => setter(root, f(getter(root))),
            schema
        )

    /** Creates a Focus for sum-type variant access (may be absent). */
    private[kyo] def createMaybe[Root, Value](
        getter: Root => Maybe[Value],
        setter: (Root, Value) => Root,
        path: Seq[String],
        schema: Schema[Root]
    ): Focus[Root, Value, Maybe] =
        new Focus[Root, Value, Maybe](
            path,
            root => getter(root),
            (root, mv) => mv.fold(root)(v => setter(root, v)),
            (root, f) => getter(root).fold(root)(v => setter(root, f(v))),
            schema
        )

    /** Creates a Focus for collection element access (zero or more). */
    private[kyo] def createChunk[Root, Value](
        getter: Root => Chunk[Value],
        setter: (Root, Chunk[Value]) => Root,
        path: Seq[String],
        schema: Schema[Root]
    ): Focus[Root, Value, Chunk] =
        new Focus[Root, Value, Chunk](
            path,
            root => getter(root),
            (root, vs) => setter(root, vs),
            (root, f) => setter(root, getter(root).map(f)),
            schema
        )

    // --- Compose factories for Focus chaining ---

    /** Id.focus(product field) => Id */
    private[kyo] def composeIdId[Root, Value, Value2](
        outer: Focus[Root, Value, Id],
        innerGetter: Value => Maybe[Value2],
        innerSetter: (Value, Value2) => Value,
        innerSegments: Seq[String]
    ): Focus[Root, Value2, Id] =
        createId[Root, Value2](
            root => innerGetter(outer.getter(root)).get,
            (root, v2) => outer.setter(root, innerSetter(outer.getter(root), v2)),
            outer.path ++ innerSegments,
            outer.schema
        )

    /** Id.focus(sum variant) => Maybe */
    private[kyo] def composeIdMaybe[Root, Value, Value2](
        outer: Focus[Root, Value, Id],
        innerGetter: Value => Maybe[Value2],
        innerSetter: (Value, Value2) => Value,
        innerSegments: Seq[String]
    ): Focus[Root, Value2, Maybe] =
        createMaybe[Root, Value2](
            root => innerGetter(outer.getter(root)),
            (root, v2) => outer.setter(root, innerSetter(outer.getter(root), v2)),
            outer.path ++ innerSegments,
            outer.schema
        )

    /** Maybe.focus(anything) => Maybe (Maybe max Id = Maybe, Maybe max Maybe = Maybe) */
    private[kyo] def composeMaybeAny[Root, Value, Value2](
        outer: Focus[Root, Value, Maybe],
        innerGetter: Value => Maybe[Value2],
        innerSetter: (Value, Value2) => Value,
        innerSegments: Seq[String]
    ): Focus[Root, Value2, Maybe] =
        createMaybe[Root, Value2](
            root => outer.getter(root).flatMap(innerGetter),
            (root, v2) => outer.getter(root).fold(root)(v => outer.setter(root, Maybe(innerSetter(v, v2)))),
            outer.path ++ innerSegments,
            outer.schema
        )

    /** Chunk.focus(anything) => Chunk (Chunk max anything = Chunk) */
    private[kyo] def composeChunkAny[Root, Value, Value2](
        outer: Focus[Root, Value, Chunk],
        innerGetter: Value => Maybe[Value2],
        innerSetter: (Value, Value2) => Value,
        innerSegments: Seq[String]
    ): Focus[Root, Value2, Chunk] =
        val combinedPath = outer.path ++ innerSegments
        val getterFn: Root => Chunk[Value2] = root =>
            outer.getter(root).flatMap(v => innerGetter(v).fold(Chunk.empty[Value2])(v2 => Chunk(v2)))
        val setterFn: (Root, Chunk[Value2]) => Root = (root, v2s) =>
            val vs        = outer.getter(root)
            val updated   = vs.zip(v2s).map((v, v2) => innerSetter(v, v2))
            val remaining = vs.drop(v2s.size)
            outer.setter(root, updated ++ remaining)
        val updateFn: (Root, Value2 => Value2) => Root = (root, f) =>
            setterFn(root, getterFn(root).map(f))
        new Focus[Root, Value2, Chunk](combinedPath, getterFn, setterFn, updateFn, outer.schema)
    end composeChunkAny

    // --- Foreach compose factories ---

    /** Id.foreach => Chunk */
    private[kyo] def composeIdForeach[Root, Value, C <: Seq[?], E](
        outer: Focus[Root, Value, Id],
        innerGetter: Value => Maybe[C],
        innerSetter: (Value, C) => Value,
        innerSegments: Seq[String]
    ): Focus[Root, E, Chunk] =
        createChunk[Root, E](
            root => Chunk.from(innerGetter(outer.getter(root)).getOrElse(Chunk.empty).asInstanceOf[Seq[E]]),
            (root, es) =>
                val v        = outer.getter(root)
                val original = innerGetter(v).getOrElse(Chunk.empty).asInstanceOf[Seq[E]]
                outer.setter(root, innerSetter(v, original.iterableFactory.from(es).asInstanceOf[C]))
            ,
            outer.path ++ innerSegments,
            outer.schema
        )

    /** Maybe.foreach => Chunk */
    private[kyo] def composeMaybeForeach[Root, Value, C <: Seq[?], E](
        outer: Focus[Root, Value, Maybe],
        innerGetter: Value => Maybe[C],
        innerSetter: (Value, C) => Value,
        innerSegments: Seq[String]
    ): Focus[Root, E, Chunk] =
        createChunk[Root, E](
            root =>
                outer.getter(root).fold(Chunk.empty[E])(v =>
                    Chunk.from(innerGetter(v).getOrElse(Chunk.empty).asInstanceOf[Seq[E]])
                ),
            (root, es) =>
                outer.getter(root).fold(root) { v =>
                    val original = innerGetter(v).getOrElse(Chunk.empty).asInstanceOf[Seq[E]]
                    val updated  = innerSetter(v, original.iterableFactory.from(es).asInstanceOf[C])
                    outer.setter(root, Maybe(updated))
                },
            outer.path ++ innerSegments,
            outer.schema
        )

    /** Chunk.foreach => Chunk (nested collection, flatten) */
    private[kyo] def composeChunkForeach[Root, Value, C <: Seq[?], E](
        outer: Focus[Root, Value, Chunk],
        innerGetter: Value => Maybe[C],
        innerSetter: (Value, C) => Value,
        innerSegments: Seq[String]
    ): Focus[Root, E, Chunk] =
        val combinedPath = outer.path ++ innerSegments
        val getterFn: Root => Chunk[E] = root =>
            outer.getter(root).flatMap(v =>
                Chunk.from(innerGetter(v).getOrElse(Chunk.empty).asInstanceOf[Seq[E]])
            )
        val setterFn: (Root, Chunk[E]) => Root = (root, es) =>
            val vs = outer.getter(root)
            val (updated, _) = vs.foldLeft((Chunk.empty[Value], 0)) { case ((acc, offset), v) =>
                val inner   = innerGetter(v).getOrElse(Chunk.empty).asInstanceOf[Seq[E]]
                val slice   = es.slice(offset, offset + inner.size)
                val rebuilt = inner.iterableFactory.from(slice).asInstanceOf[C]
                (acc :+ innerSetter(v, rebuilt), offset + inner.size)
            }
            outer.setter(root, updated)
        val updateFn: (Root, E => E) => Root = (root, f) =>
            setterFn(root, getterFn(root).map(f))
        new Focus[Root, E, Chunk](combinedPath, getterFn, setterFn, updateFn, outer.schema)
    end composeChunkForeach

end Focus
