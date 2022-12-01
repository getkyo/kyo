package kyoTest

import kyo.batches._
import kyo.core._
import kyo.options._

class batchesTest extends KyoTest {

  "foreach" - {
    "one" in {
      val v1: Int > Batches = Batches.foreach(List(1, 2))
      assert((v1 << Batches).run == List(1, 2))
    }
    "nested" in {
      val v1: Int > Batches =
        Batches.foreach(List(1, 2))
      val v2: Int > (Batches | Batches) =
        v1(i => Batches.foreach(List(i * 2, i * 3)))
      assert((v2 << Batches).run == List(2, 3, 4, 6))
    }
    "multiple nested" in {
      val v1: Int > Batches =
        Batches.foreach(List(1, 2))
      val v2: Int > (Batches | Batches) =
        v1(i => Batches.foreach(List(i * 2, i * 3)))
      val v3: Int > (Batches | Batches) =
        v2(i => Batches.foreach(List(10, i)))
      assert((v3 << Batches).run == List(10, 2, 10, 3, 10, 4, 10, 6))
    }
    "multiple nested with transformations" in {
      val v1: Int > Batches =
        Batches.foreach(List(1, 2))(_ + 1)
      val v2: Int > (Batches | Batches) =
        v1(i => Batches.foreach(List(i * 2, i * 3)))(_ + 1)
      val v3: Int > (Batches | Batches) =
        v2(i => Batches.foreach(List(10, i)))(_ + 1)
      assert((v3 << Batches).run == List(11, 6, 11, 8, 11, 8, 11, 11))
    }
  }

  "forall" - {
    "one" in {
      var call: List[Int] = null
      val f: Int => Int > Batches = Batches.forall[Int, Int] { l =>
        call = l
        l.map(_ + 1)
      }
      val v1: Int > Batches = Batches.foreach(List(1, 2))
      val v2: Int > Batches = v1(f)
      assert((v2 << Batches).run == List(2, 3))
      assert(call == List(1, 2))
    }
    "nested" in {
      var call: List[Int] = null
      val f = Batches.forall[Int, Int] { l =>
        call = l
        l.map(_ + 1)
      }
      val v1: Int > Batches =
        Batches.foreach(List(1, 2)) { i =>
          Batches.foreach(List(i * 2, i * 3))
        }
      val v2: Int > Batches = v1(f)(_ + 1)
      assert((v2 << Batches).run == List(4, 5, 6, 8))
      assert(call == List(2, 3, 4, 6))
    }
    "mixed batched / unbatched" in {
      var call: List[Int] = null
      val f = Batches.forall[Int, Int] { l =>
        call = l
        l.map(_ + 1)
      }
      val v1: Int > Batches =
        Batches.foreach(List(1, 2)) { i =>
          Batches.foreach(List(i * 2, i * 3))
        }
      val v2: Int > Batches = v1 { i =>
        if (i < 4) i
        else f(i)
      }(_ + 1)
      assert((v2 << Batches).run == List(3, 4, 6, 8))
      assert(call == List(4, 6))
    }
    "with other effect" in {
      var call: List[Int] = null
      val f: Int => Int > Batches = Batches.forall[Int, Int] { l =>
        call = l
        l.map(_ + 1)
      }
      val v1: Int > Batches = Batches.foreach(List(1, 2, 3, 4))
      val v2: Int > (Batches | Options) = v1 { i =>
        if (i > 2) (Option(i) > Options)(f)
        else f(i)
      }
      assert((v2 < Options << Batches).run == List(Some(2), Some(3), Some(4), Some(5)))
      assert(call == List(1, 2, 3, 4))
    }
    "with other effect + mixed batched / unbatched" in {
      var call: List[Int] = null
      val f = Batches.forall[Int, Int] { l =>
        call = l
        l.map(_ + 1)
      }
      val v1: Int > Batches =
        Batches.foreach(List(1, 2)) { i =>
          Batches.foreach(List(i * 2, i * 3))
        }
      val v2: Int > (Batches | Options) = v1 { i =>
        if (i < 4) Option(i) > Options
        else f(i)
      }
      assert((v2 < Options << Batches).run == List(Some(2), Some(3), Some(5), Some(7)))
      assert(call == List(4, 6))
    }
    "multiple" in {
      var call1: List[Int] = null
      var call2: List[Int] = null
      val f1: Int => Int > Batches = Batches.forall[Int, Int] { l =>
        call1 = l
        l.map(_ * 10)
      }
      val f2: Int => Int > Batches = Batches.forall[Int, Int] { l =>
        call2 = l
        l.map(_ * 100)
      }
      val v1: Int > Batches = Batches.foreach(List(1, 2, 3, 4))
      val v2: Int > Batches = v1 { i =>
        if(i < 3) f1(i)
        else f2(i)
      }
      assert((v2 << Batches).run == List(10, 20, 300, 400))
      assert(call1 == List(1, 2))
      assert(call2 == List(3, 4))
    }
  }
}
