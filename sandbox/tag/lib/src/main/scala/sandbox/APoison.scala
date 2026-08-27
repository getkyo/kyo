package sandbox

import kyo.Tag

import scala.compiletime.testing.typeCheckErrors

/** Cache poisoning. The macro memoizes encodings by type, and a lookup that ran before the scope
  * check would hand an out-of-scope encoding back inside a scope. The derivation and the probe are
  * siblings in one template so they are typed in source order, whatever order files compile in.
  */
object Poison:
    object First:
        val long: Tag[Long] = Tag.derive[Long]
    object Second:
        opaque type D = Long
        object D:
            val longErrors: List[String] = errors(typeCheckErrors("Tag.derive[Long]"))
    end Second
end Poison
