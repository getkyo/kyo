package kyo.experimental

import kyo.*

class ServiceTest extends kyo.Test:

    "composition - and" in {
        trait Svc1[-S]:
            def f(i: Int): Int < S
        trait Svc2[-S]:
            def g(s: String): String < S

        val s1: Service[Svc1, Sync] = Service {
            new Svc1[Sync]:
                def f(i: Int): Int < Sync = i + 1
        }
        val s2: Service[Svc2, Sync] = Service {
            new Svc2[Sync]:
                def g(s: String): String < Sync = s + "!"
        }

        val both: Service[Svc1 && Svc2, Sync] = s1 and s2
        discard(both)
        succeed
    }

    "composition - provide (dependent service)" in {
        trait Base[-S]:
            def base: Int < S
        trait Dep[-S]:
            def dep: Int < S

        val base: Service[Base, Sync] = Service(
            new Base[Sync]:
                def base: Int < Sync = 41
        )

        // Dep depends on Base at construction time (via Use[Base])
        val dep: Service[Dep, Sync & Use[Base]] = Service.using[Base](base =>
            new Dep[Use[Base]]:
                def dep: Int < Use[Base] = base.base.map(_ + 1)
        )

        val provided: Service[Base && Dep, Sync] = base.provide(dep)
        discard(provided)
        succeed
    }

    // The following tests are sketches mirroring LayerTest-style runnable scenarios.
    // There isn't a public runner that turns a Service[...] into a Use[...] runtime yet.
    // Leaving them commented as requested (they may not compile until such runner exists).

    /*
  "run service graph similar to Layer.runLayer" in run {
    trait Foo[-S]:
      def n: Int < S
    trait Bar[-S]:
      def s: String < S

    val foo: Service[Foo, Sync] = Service {
      new Foo[Sync]:
        def n: Int < Sync = 5
    }
    val bar: Service[Bar, Use[Foo] & Sync] = Service {
      Use.use[Foo](f => new Bar[Sync]:
        def s: String < Sync = f.n.map(n => s"n=$n")
      )
    }

    val graph = foo.provide(bar)

    // Hypothetical API:
    // Service.run(graph) {
    //   Use.use[Foo && Bar] { r =>
    //     r.n.zip(r.s).map { (n, s) =>
    //       assert(n == 5)
    //       assert(s == "n=5")
    //     }
    //   }
    // }
    succeed
  }
     */

    /*
  "effects interaction (Abort/Var) in service construction" in {
    trait Cnt[-S]:
      def get: Int < S

    val cnt: Service[Cnt, Var[Int] & Abort[String]] = Service {
      Var.update[Int](_ + 1).andThen:
        if 0 == 1 then Abort.fail("impossible")
        else new Cnt[Sync]:
          def get: Int < Sync = Var.get[Int]
    }

    discard(cnt)
    succeed
  }
     */

end ServiceTest
