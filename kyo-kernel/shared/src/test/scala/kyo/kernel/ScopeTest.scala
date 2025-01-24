// package kyo.kernel

// import kyo.*

// sealed trait TContextEffect extends ContextEffect[Int]

// sealed trait TArrowEffect extends ArrowEffect[Const[Int], Const[Int]]
// object TArrowEffect:
//     given Scope.Isolate[TArrowEffect] = ???

// sealed trait TArrowEffect2 extends ArrowEffect[Const[Int], Const[Int]]
// object TArrowEffect2:
//     given Scope.Isolate[TArrowEffect2] = ???

// object ttt:
//     def test1[A, S, S2: Scope](v: A < (S & S2))         = 1
//     def test2[A, S: Scope](v: A < (S & TArrowEffect))   = 1
//     def test3[A, S: Scope](v: A < (S & TContextEffect)) = 1

//     val a = test3(1: Int < (TArrowEffect & TContextEffect))
// end ttt
