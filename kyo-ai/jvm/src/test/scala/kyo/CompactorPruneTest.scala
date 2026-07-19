package kyo

import Compactor.internal.*
import kyo.ai.*
import kyo.ai.Context.*

/** JVM-only: the compactor cell's GC-prune DROP path, exercised deterministically.
  *
  * A collected instance is simulated by `WeakReference.clear()` (an API Scala.js, and therefore the JS and
  * Wasm targets, does not implement), so the drop cannot be driven deterministically in the shared suite.
  * The shared `CompactorTest` covers the keep path (a live entry survives the prune a render runs); this
  * leaf covers the drop path (a cleared ref's entry is removed).
  */
class CompactorPruneTest extends kyo.test.Test[Any]:

    def um(s: String): UserMessage     = UserMessage(s, Absent)
    def ctxOf(msgs: Message*): Context = Context(Chunk.from(msgs))

    "a render prunes a collected (cleared) ref's entry, keeping the live one" in {
        Compactor.init(_.copy(effectiveCap = 100000, windowFraction = 1.0)).map { c =>
            LLM.run {
                AI.initWith { live =>
                    val liveRef = LLM.internal.AIRef(live)
                    val deadRef = new LLM.internal.AIRef(new AI(987654321L, new AnyRef))
                    deadRef.clear() // referent gone: isValid becomes false, as after a real GC
                    assert(!deadRef.isValid, "a cleared ref reports invalid")
                    val ctx = ctxOf(um("hello"))
                    c.cell.set(Dict((liveRef, CompactorState.empty), (deadRef, CompactorState.empty))).andThen {
                        live.setContext(ctx).andThen(c.render(live, ctx)).andThen {
                            c.cell.get.map { d =>
                                assert(!d.contains(deadRef), s"a render prunes the collected ref's entry: ${d.size} entries")
                                assert(d.contains(liveRef), "the live instance's entry survives the prune")
                            }
                        }
                    }
                }
            }
        }
    }

end CompactorPruneTest
