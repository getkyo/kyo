package kyo.ffi.codegen.model

/** A binding method, one per abstract method on an `Ffi`-extending trait.
  *
  * @param scalaName
  *   the method's Scala name, e.g. `"tcpConnect"`.
  * @param cSymbol
  *   the resolved C symbol per `Ffi.Config` (explicit override, prefix + snake_case, or plain snake_case).
  * @param params
  *   parameter list in declaration order. If [[hasVarargs]] is `true`, this list contains ONLY the fixed (non-varargs) parameters, the
  *   trailing `Any*` varargs parameter is appended by emitters to the generated signature and threaded via runtime-class dispatch.
  * @param returnShape
  *   shape of the return value (see [[ReturnShape]]).
  * @param blocking
  *   true when the method carries the `@Ffi.blocking` annotation.
  * @param hasArrayParam
  *   convenience, `true` iff any parameter is an `Array[A]`.
  * @param callbackKind
  *   derived from signature shape (see [[CallbackKind]]).
  * @param hasVarargs
  *   true when the binding's last parameter is a Scala varargs `Any*`, declaring a C-level variadic function. The varargs parameter is NOT
  *   listed in [[params]]; it is synthesized by emitters as a trailing `args: Any*` on the generated method.
  * @param withError
  *   true when the binding method's Scala return type is `WithError[A]`. The emitter wraps the C return value together with the captured
  *   errno into a `kyo.ffi.WithError` instead of throwing [[kyo.ffi.FfiErrno]] on non-zero errno.
  */
final case class MethodSpec(
    scalaName: String,
    cSymbol: String,
    params: List[ParamSpec],
    returnShape: ReturnShape,
    blocking: Boolean,
    hasArrayParam: Boolean,
    callbackKind: CallbackKind,
    hasVarargs: Boolean = false,
    withError: Boolean = false
)

/** One parameter in a [[MethodSpec]]. */
final case class ParamSpec(name: String, tpe: TypeRef)

/** Shape of a method's return value. */
sealed trait ReturnShape derives CanEqual

object ReturnShape:
    /** C returns `void`, Scala declares `Unit`. */
    case object Void extends ReturnShape

    /** C returns a value, Scala declares the same primitive. */
    final case class Primitive(t: TypeRef) extends ReturnShape

    /** C returns a struct. */
    final case class Struct(spec: StructSpec) extends ReturnShape

    /** C uses out-params; Scala returns a case class whose first field is the C return value and the remaining fields are out-params in
      * declaration order.
      */
    final case class MultiValue(spec: StructSpec) extends ReturnShape

    /** C returns a borrowed NUL-terminated string (`char*` owned by the callee).
      *
      * The generator decodes the pointer into a Scala `String` and COPIES the bytes, the returned `String` is owned by Scala and the
      * original C memory remains the callee's. Scan window is bounded by [[maxBytes]] (default from `-Dkyo.ffi.stringFieldMaxBytes=`);
      * exceeding the cap throws `FfiMalformedResult`.
      *
      * Emitted for methods whose Scala return type is `Borrowed[String]`.
      */
    final case class BorrowedString(maxBytes: Int) extends ReturnShape

    /** C returns a borrowed pointer to a region of [[elemType]] elements whose count comes from the method parameter named [[sizeParam]].
      *
      * The generator wraps the pointer via `Buffer.Unsafe.wrapBorrowed` (or `wrapBorrowedChecked` when `Ffi.Config.checkedBorrows` /
      * `-Dkyo.ffi.checkedBorrows=true`). `Buffer.close` is a no-op, the C side retains ownership.
      *
      * Emitted for methods whose Scala return type is `Borrowed[Buffer[elemType]]`. The size parameter is inferred from the method's
      * Int/Long parameters (exactly one required).
      */
    final case class BorrowedBuffer(elemType: TypeRef, sizeParam: String) extends ReturnShape

    /** C returns an opaque pointer, wrapped in a `Handle[A]`.
      *
      * @param typeArgFqcn
      *   fully-qualified name of the phantom type argument (e.g. `"kyo.ffi.it.ItHandle"`).
      * @param nullable
      *   when `false` (bare `Handle[A]` return), NULL from C throws [[kyo.ffi.FfiNullPointer]]; when `true` (`Maybe[Handle[A]]` return),
      *   NULL maps to `Absent` and non-null maps to `Present(handle)`.
      */
    final case class HandleReturn(typeArgFqcn: String, nullable: Boolean = false) extends ReturnShape

    /** C returns an int that maps to a structurally-detected Scala 3 enum case.
      *
      * The code generator emits `FqcnCompanion.fromInt(rawInt)` to convert the raw C int into the enum case.
      *
      * @param fqcn
      *   fully-qualified name of the enum (e.g. `"kyo.ffi.it.ItColor"`).
      */
    final case class EnumReturn(fqcn: String) extends ReturnShape
end ReturnShape

/** Callback classification, derived from method signature shape. */
enum CallbackKind derives CanEqual:
    /** No function-typed parameter. */
    case None

    /** One or more function-typed parameters, no `Ffi.Guard` parameter, callback lifetime is the FFI call. */
    case Transient

    /** One or more function-typed parameters together with an `Ffi.Guard` parameter, callback lifetime extends until guard close. */
    case Retained
end CallbackKind
