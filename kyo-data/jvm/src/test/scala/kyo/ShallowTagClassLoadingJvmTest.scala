package kyo

import java.lang.ref.WeakReference
import scala.annotation.tailrec

class ShallowTagClassLoadingJvmTest extends kyo.test.Test[Any]:

    import ShallowTagClassLoadingJvmTest.*

    "a loader nothing references is collected" in {
        val loader = isolatedLoader(cacheEmpty = false)
        assert(collected(loader.ref))
    }

    "the empty array cache does not keep a class or its loader alive" in {
        val loader = isolatedLoader(cacheEmpty = true)
        assert(loader.cachedTyped)
        assert(collected(loader.ref))
    }

end ShallowTagClassLoadingJvmTest

object ShallowTagClassLoadingJvmTest:

    class Probe

    final class IsolatedLoader extends ClassLoader(null):
        def defineProbe(): Class[?] =
            val name  = classOf[Probe].getName
            val bytes = classOf[Probe].getResourceAsStream("/" + name.replace('.', '/') + ".class").readAllBytes()
            defineClass(name, bytes, 0, bytes.length)
        end defineProbe
    end IsolatedLoader

    final case class Loaded(ref: WeakReference[ClassLoader], cachedTyped: Boolean)

    // Returns only a weak reference and a flag, so no caller frame or assertion holds the class.
    def isolatedLoader(cacheEmpty: Boolean): Loaded =
        val loader      = new IsolatedLoader
        val cls         = loader.defineProbe()
        val cachedTyped =
            !cacheEmpty || {
                val empty = ShallowTag.fromClass[Any](cls).emptyArray
                !(cls eq classOf[Probe]) &&
                (empty.getClass.getComponentType eq cls) &&
                (ShallowTag.fromClass[Any](cls).emptyArray eq empty)
            }
        Loaded(new WeakReference(loader), cachedTyped)
    end isolatedLoader

    // `refersTo` rather than `get`: a referent read into a local stays strongly reachable from the frame during the next `gc`.
    def collected(ref: WeakReference[ClassLoader]): Boolean =
        @tailrec def loop(rounds: Int): Boolean =
            if ref.refersTo(null) then true
            else if rounds == 0 then false
            else
                java.lang.System.gc()
                loop(rounds - 1)
        loop(20)
    end collected

end ShallowTagClassLoadingJvmTest
