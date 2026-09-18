package kyo.stats.internal

import java.lang.ref.Reference
import java.lang.ref.WeakReference
import org.scalatest.freespec.AnyFreeSpec

/** The registry holds its instruments through `java.lang.ref.WeakReference`, and on the JVM and Native that has to be the platform's
  * own class. This module ships a strong-reference stand-in under the same name for JS and Wasm, whose platform has no `java.lang.ref`;
  * linked on Native, it took the place of the javalib's for every binary depending on this module, and the javalib's `ThreadLocal`
  * keys its entries with it: a stand-in that is not a `Reference` fails the type test `ThreadLocal.Values.rehash` migrates entries
  * through, so every `ThreadLocal` on a thread lost its entry when the thread's table first grew, at ten entries.
  */
class StatsRegistryReferenceTest extends AnyFreeSpec {

    "a WeakReference is a Reference" in {
        val ref: AnyRef = new WeakReference[AnyRef](new Object)
        assert(ref.isInstanceOf[Reference[?]], "java.lang.ref.WeakReference is not the platform's: it does not extend Reference")
    }

    "a WeakReference answers its referent" in {
        val referent = new Object
        assert(new WeakReference[AnyRef](referent).get() eq referent)
    }

    "every ThreadLocal keeps its entry while the thread's table grows" in {
        val n      = 24
        val locals = Array.tabulate(n)(_ => new ThreadLocal[AnyRef] { override def initialValue(): AnyRef = new Object })
        val first  = new Array[AnyRef](n)
        var lost   = List.empty[String]
        var i      = 0
        while (i < n) {
            first(i) = locals(i).get()
            var j = 0
            while (j <= i) {
                if (locals(j).get() ne first(j)) lost = s"entry #${j + 1} after inserting entry #${i + 1}" :: lost
                j += 1
            }
            i += 1
        }
        assert(lost.isEmpty, s"ThreadLocal entries lost: ${lost.reverse.take(5).mkString(", ")}")
    }
}
