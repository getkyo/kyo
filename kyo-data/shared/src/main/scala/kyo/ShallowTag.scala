package kyo

import kyo.internal.EmptyArrays
import kyo.internal.ShallowTagMacro

/** The runtime class a type erases to, as evidence for allocating arrays of it.
  *
  * A ShallowTag keeps only the outer class of a type: `List[Int]` and `List[String]` both have the tag of `List`. It is the class Scala
  * erases `A` to, so an opaque type has the tag of its underlying type (`Duration` is `Long`), a union or intersection has the tag of its
  * erased bound, and the top types `Any`, `AnyVal` and `AnyRef` have the tag of `Object`. An array allocated from a ShallowTag has exactly
  * the runtime class that `new Array[A]` gives with a compiler-synthesized ClassTag.
  *
  * Because it drops type arguments, a ShallowTag can be derived for every concrete type, and its membership check (`accepts`,
  * `unapply`) is as shallow as the tag: a `List[String]` passes for `ShallowTag[List[Int]]`, and every value passes for a union that
  * erases to `Object`. This is the check `ClassTag` performs. Use [[ConcreteTag]] when membership must be exact: it refuses the types
  * whose membership the runtime cannot decide.
  *
  * Derivation happens at compile time and yields a class constant, so summoning one costs nothing at runtime. An abstract type has no
  * ShallowTag of its own: code generic in `A` must take `(using ShallowTag[A])` from its caller. `Nothing` and `Null` have no ShallowTag.
  *
  * Arrays of length zero are cached per class and shared: `emptyArray` and `newArray(0)` allocate at most once per class.
  *
  * @tparam A
  *   The type whose erased runtime class this tag holds
  */
@scala.annotation.implicitNotFound(
    "No ShallowTag for ${A}, which needs a concrete type. " +
        "An element type inferred as Nothing needs naming, as in Span.empty[Int]; " +
        "an abstract type needs (using ShallowTag[${A}]) from the caller."
)
opaque type ShallowTag[A] = Class[?]

object ShallowTag:

    inline given [A, B]: CanEqual[ShallowTag[A], ShallowTag[B]] = CanEqual.derived

    inline given derive[A]: ShallowTag[A] = ${ ShallowTagMacro.derive[A] }

    def apply[A: ShallowTag as tag]: ShallowTag[A] = tag

    /** Creates a ShallowTag from a runtime class.
      *
      * The primitive class `void` maps to `BoxedUnit`, the class Scala stores `Unit` values under in arrays.
      *
      * @param cls
      *   the runtime class values of `A` are instances of
      * @return
      *   a ShallowTag holding that class
      */
    def fromClass[A](cls: Class[?]): ShallowTag[A] =
        if cls eq java.lang.Void.TYPE then classOf[scala.runtime.BoxedUnit] else cls

    /** Creates a ShallowTag from an existing array's component class.
      *
      * @param array
      *   the array to inspect
      * @return
      *   a ShallowTag whose arrays have the same runtime class as `array`
      */
    def fromArray[A](array: Array[A]): ShallowTag[A] =
        array.getClass.getComponentType

    extension [A](self: ShallowTag[A])

        /** The runtime class `A` erases to: a primitive class such as `Integer.TYPE` for a primitive `A`, otherwise a reference class. */
        def erasedClass: Class[?] = self

        /** Allocates an array of `A` with the runtime class Scala uses for `Array[A]`.
          *
          * @param len
          *   the length of the array
          * @return
          *   a new array, or the shared empty array of this class when `len` is zero
          */
        def newArray(len: Int): Array[A] =
            if len == 0 then emptyArray
            else
                val cls = self
                (if !cls.isPrimitive then java.lang.reflect.Array.newInstance(cls, len)
                 else if cls eq java.lang.Integer.TYPE then new Array[Int](len)
                 else if cls eq java.lang.Long.TYPE then new Array[Long](len)
                 else if cls eq java.lang.Double.TYPE then new Array[Double](len)
                 else if cls eq java.lang.Byte.TYPE then new Array[Byte](len)
                 else if cls eq java.lang.Character.TYPE then new Array[Char](len)
                 else if cls eq java.lang.Boolean.TYPE then new Array[Boolean](len)
                 else if cls eq java.lang.Float.TYPE then new Array[Float](len)
                 else new Array[Short](len)) .asInstanceOf[Array[A]]
            end if
        end newArray

        /** The shared zero-length array of this class, allocated at most once per class. */
        def emptyArray: Array[A] = emptyOf(self).asInstanceOf[Array[A]]

        /** Tests whether a value is an instance of the class `A` erases to. A primitive `A` accepts its boxed values; `null` is never
          * accepted. Type arguments are not checked.
          *
          * @param value
          *   the value to test
          * @return
          *   true if `value` is an instance of this tag's erased class
          */
        def accepts(value: Any): Boolean =
            !isNull(value) && boxedOf(self).isInstance(value)

        /** Extracts a value as an `A` when `accepts` holds, for use in pattern matching (`case tag(a) =>`).
          *
          * @param value
          *   the value to test
          * @return
          *   the value typed as `A`, or empty when it is not an instance of this tag's erased class
          */
        def unapply(value: Any): Maybe.Ops[A] =
            if accepts(value) then Maybe(value.asInstanceOf[A]) else Maybe.empty
    end extension

    private def boxedOf(cls: Class[?]): Class[?] =
        if !cls.isPrimitive then cls
        else if cls eq java.lang.Integer.TYPE then classOf[java.lang.Integer]
        else if cls eq java.lang.Long.TYPE then classOf[java.lang.Long]
        else if cls eq java.lang.Double.TYPE then classOf[java.lang.Double]
        else if cls eq java.lang.Byte.TYPE then classOf[java.lang.Byte]
        else if cls eq java.lang.Character.TYPE then classOf[java.lang.Character]
        else if cls eq java.lang.Boolean.TYPE then classOf[java.lang.Boolean]
        else if cls eq java.lang.Float.TYPE then classOf[java.lang.Float]
        else classOf[java.lang.Short]
    end boxedOf

    private def emptyOf(cls: Class[?]): Array[?] =
        if !cls.isPrimitive then EmptyArrays.get(cls)
        else if cls eq java.lang.Integer.TYPE then Array.emptyIntArray
        else if cls eq java.lang.Long.TYPE then Array.emptyLongArray
        else if cls eq java.lang.Double.TYPE then Array.emptyDoubleArray
        else if cls eq java.lang.Byte.TYPE then Array.emptyByteArray
        else if cls eq java.lang.Character.TYPE then Array.emptyCharArray
        else if cls eq java.lang.Boolean.TYPE then Array.emptyBooleanArray
        else if cls eq java.lang.Float.TYPE then Array.emptyFloatArray
        else Array.emptyShortArray
    end emptyOf

end ShallowTag
