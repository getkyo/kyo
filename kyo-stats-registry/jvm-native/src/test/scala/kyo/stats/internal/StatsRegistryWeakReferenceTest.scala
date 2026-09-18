package kyo.stats.internal

import java.lang.ref.Reference
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.freespec.AnyFreeSpec

class StatsRegistryWeakReferenceTest extends AnyFreeSpec {

    "registry dependencies preserve the platform weak-reference hierarchy" in {
        val value             = new Object
        val reference: AnyRef = new WeakReference(value)
        assert(reference.isInstanceOf[Reference[?]])
        val platformReference = reference.asInstanceOf[Reference[Object]]
        assert(platformReference.get() eq value)
        platformReference.clear()
        assert(platformReference.get() == null)
    }

    "a cleared registry reference is replaced with the newly initialized value" in {
        val store  = new StatsRegistry.internal.Store[Object]
        val path   = List("weak-reference-regression")
        val first  = new Object
        val second = new Object
        assert(store.get(path, "first", first) eq first)
        val reference = store.map.get(path)._1.asInstanceOf[Reference[Object]]
        reference.clear()
        assert(store.get(path, "second", second) eq second)
        assert(store.map.get(path)._1.get() eq second)
        assert(store.map.size() == 1)
    }

    // The symptom a stand-in shadowing the platform class produced on Native: the javalib's ThreadLocal keys its entries
    // with WeakReference and migrates them through a `Reference` type test when the table grows (at ten entries), so
    // every ThreadLocal on the thread lost its entry at that point.
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

    "inherited thread-local values are copied when a child thread is constructed" in {
        val copies = new AtomicInteger
        val local = new InheritableThreadLocal[AtomicInteger] {
            override def childValue(parent: AtomicInteger): AtomicInteger = {
                val _ = copies.incrementAndGet()
                new AtomicInteger(parent.get())
            }
        }
        val parent = new AtomicInteger(42)
        local.set(parent)
        try {
            // Construction invokes childValue on the parent thread; no worker needs to start or block.
            val child = new Thread(() => ())
            assert(child.getState() eq Thread.State.NEW)
            assert(copies.get() == 1)
            assert(local.get() eq parent)
            assert(parent.get() == 42)
        } finally local.remove()
    }
}
