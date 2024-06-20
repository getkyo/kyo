// package kyo2.kernel

// import kyo2.Test

// class ReducibleTest extends Test:

//     trait Effect1 extends Effect[Const[Unit], Const[Unit]]
//     trait Effect2 extends Effect[Const[Unit], Const[Unit]]
//     trait Effect3 extends Effect[Const[Unit], Const[Unit]]

//     "Remove" - {
//         import Reducible.Remove
//         "remove one" in {
//             def test[S](a: Int < S)(using r: Remove[S, Effect2]) = r(a)
//             val x: Int < (Effect1 & Effect3) =
//                 test[Effect1 & Effect2 & Effect3](1)
//             assertDoesNotCompile("val _: Int < Effect1 = test[Effect1 & Effect2 & Effect3](1)")
//         }

//         "remove two" in {
//             def test[S](a: Int < S)(using r: Remove[S, Effect2 & Effect3]) = r(a)
//             val x: Int < Effect1 =
//                 test[Effect1 & Effect2 & Effect3](1)
//             assertDoesNotCompile("val _: Int < Any = test[Effect1 & Effect2 & Effect3](1)")
//         }

//         "parametrized" - {

//             "invariant" in {
//                 trait EffectInv[T] extends Effect[Const[Unit], Const[Unit]]
//                 def test[S, T](a: Int < S)(using remove: Remove[S, EffectInv[T]]) =
//                     remove(a)
//                 val x: Int < (Effect1 & Effect3) = test[Effect1 & EffectInv[String] & Effect3, String](1)
//                 assertDoesNotCompile(
//                     "val _: Int < Effect1 = test[Effect1 & EffectInv[String] & Effect3, String](1)"
//                 )
//             }

//             "covariant" - {
//                 "single type" in {
//                     trait EffectCov[+T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectCov[T]]) = remove(a)
//                     val x: Int < (Effect1 & Effect3) = test[Effect1 & EffectCov[String] & Effect3, String](1)
//                     assertDoesNotCompile("val _: Int < Effect1 = test[Effect1 & EffectCov[String] & Effect3, String](1)")
//                 }

//                 "union two types remove one" in {
//                     trait EffectCov[+T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectCov[Int]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & EffectCov[String] & Effect3) =
//                         test[Effect1 & EffectCov[String & Int] & Effect3, String](1)
//                     assertDoesNotCompile("val _: Int < (Effect1 & Effect3) = test[Effect1 & EffectCov[String & Int] & Effect3, String](1)")
//                 }

//                 "union two types remove both" in {
//                     trait EffectCov[+T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectCov[Int & String]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & Effect3) =
//                         test[Effect1 & EffectCov[String & Int] & Effect3, String](1)
//                     assertDoesNotCompile(
//                         "val _: Int < Effect1 = test[Effect1 & EffectCov[String & Int] & Effect3, String](1)"
//                     )
//                 }

//                 "union three types remove two" in {
//                     trait EffectCov[+T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectCov[Int & String]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & EffectCov[Double] & Effect3) =
//                         test[Effect1 & EffectCov[String & Int & Double] & Effect3, String](1)
//                     assertDoesNotCompile(
//                         "val _: Int < (Effect1 & Effect3) = test[Effect1 & EffectCov[String & Int & Double] & Effect3, String](1)"
//                     )
//                 }

//                 "union three types remove all" in {
//                     trait EffectCov[+T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectCov[Int & String & Double]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & Effect3) =
//                         test[Effect1 & EffectCov[String & Int & Double] & Effect3, String](1)
//                     assertDoesNotCompile(
//                         "val _: Int < (Effect1 & EffectCov[Double] & Effect3) = test[Effect1 & EffectCov[String & Int & Double] & Effect3, String](1)"
//                     )
//                 }
//             }

//             "contravariant" - {
//                 "single type" in {
//                     trait EffectConv[-T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectConv[T]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & Effect3) =
//                         test[Effect1 & EffectConv[String] & Effect3, String](1)
//                     assertDoesNotCompile(
//                         "val _: Int < (Effect1 & EffectConv[String] & Effect3) = test[Effect1 & EffectConv[String] & Effect3, String](1)"
//                     )
//                 }

//                 "intersection two types remove one" in {
//                     trait EffectConv[-T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectConv[Int]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & EffectConv[String] & Effect3) =
//                         test[Effect1 & EffectConv[String | Int] & Effect3, String](1)
//                     assertDoesNotCompile("val _: Int < (Effect1 & Effect3) = test[Effect1 & EffectConv[String | Int] & Effect3, String](1)")
//                 }

//                 "intersection two types remove both" in {
//                     trait EffectConv[-T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectConv[Int | String]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & Effect3) =
//                         test[Effect1 & EffectConv[String | Int] & Effect3, String](1)
//                     assertDoesNotCompile(
//                         "val _: Int < (Effect1 & EffectConv[String] & Effect3) = test[Effect1 & EffectConv[String | Int] & Effect3, String](1)"
//                     )
//                 }

//                 "intersection three types remove two" in {
//                     trait EffectConv[-T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectConv[Int | String]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & EffectConv[Double] & Effect3) =
//                         test[Effect1 & EffectConv[String | Int | Double] & Effect3, String](1)
//                     assertDoesNotCompile(
//                         "val _: Int < (Effect1 & Effect3) = test[Effect1 & EffectConv[String | Int | Double] & Effect3, String](1)"
//                     )
//                 }

//                 "intersection three types remove all" in {
//                     trait EffectConv[-T] extends Effect[Const[Unit], Const[Unit]]
//                     def test[S, T](a: Int < S)(using remove: Remove[S, EffectConv[Int | String | Double]]) =
//                         remove(a)
//                     val x: Int < (Effect1 & Effect3) =
//                         test[Effect1 & EffectConv[String | Int | Double] & Effect3, String](1)
//                     assertDoesNotCompile(
//                         "val _: Int < (Effect1 & EffectConv[Double] & Effect3) = test[Effect1 & EffectConv[String | Int | Double] & Effect3, String](1)"
//                     )
//                 }
//             }
//         }
//     }

// end ReducibleTest
