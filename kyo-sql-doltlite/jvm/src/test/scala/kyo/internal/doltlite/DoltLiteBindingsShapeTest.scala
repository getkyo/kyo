package kyo.internal.doltlite

import kyo.*
import kyo.Test
import kyo.internal.sqlite.SqliteBindings

/** Guards the copy this module is forced to keep: [[DoltLiteBindings]] restates [[SqliteBindings]]'s declarations rather
  * than inheriting them, because the FFI generator builds an implementation only from the methods declared DIRECTLY on
  * a trait. The two copies can drift without a compile error, and a method on one binding but not the other is a call
  * that works against one engine and fails at library load against the other.
  *
  * JVM-only because it reads the declarations reflectively. The property is about the source rather than the platform,
  * so checking it once is enough.
  */
class DoltLiteBindingsShapeTest extends Test:

    /** Instance method names on a binding trait, excluding what every trait inherits from Object and Ffi.
      *
      * The static filter is load-bearing: Scala emits a static forwarder on a trait's class for each member of its
      * companion object, so a binding whose companion is an `Ffi.Config` appears to declare `library`, `symbols` and the
      * rest. Only one of the two traits here has such a companion, so keeping them would read as drift.
      */
    private def declared(cls: Class[?]): Set[String] =
        cls.getMethods
            .filterNot(m => java.lang.reflect.Modifier.isStatic(m.getModifiers))
            .map(_.getName)
            .toSet
            .filterNot(n => n.startsWith("$") || n.contains("__"))

    "both bindings declare the same methods, so neither engine can drift out of the other's reach" in {
        val lite       = declared(classOf[DoltLiteBindings])
        val sqlite     = declared(classOf[SqliteBindings])
        val onlyLite   = (lite -- sqlite).toSeq.sorted
        val onlySqlite = (sqlite -- lite).toSeq.sorted
        assert(
            onlyLite.isEmpty && onlySqlite.isEmpty,
            s"the copied binding has drifted. Only on DoltLite: $onlyLite. Only on SQLite: $onlySqlite. " +
                "Copy the missing declarations across; the generator cannot inherit them."
        )
    }

    // Not merely equal counts: the map keys are what the generator resolves, so a method with no entry binds nothing.
    "every declared method has a symbol, and every symbol has a method" in {
        val methods  = declared(classOf[DoltLiteBindings])
        val mapped   = DoltLiteBindings.symbols.keySet
        val unmapped = (mapped -- methods).toSeq.sorted
        assert(
            unmapped.isEmpty,
            s"the symbol map names entries this trait does not declare: $unmapped"
        )
    }

end DoltLiteBindingsShapeTest
