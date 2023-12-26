// package kyo.llm.thoughts

// import scala.util.Random

// class ReductionTest extends ThoughtTest {

//   import exprs._

//   "small exprs" - {
//     "baseline" - {
//       "boolean" in {
//         test["reduce expression", Boolean](BooleanTask(5), 0.9)
//       }
//       // "math" in {
//       //   test["reduce expression", Int](MathTask(5), 0.9)
//       // }
//     }
//     // "reduction" - {
//     //   "boolean" in {
//     //     test[Reduction[String, Boolean], Boolean](BooleanTask(5), 0.5)
//     //   }
//     //   "math" in {
//     //     test[Reduction[String, Boolean], Int](MathTask(5), 0.5)
//     //   }
//     // }
//     // "reduction + refine" - {
//     //   "boolean" in {
//     //     test[Refine[Reduction[String, Boolean]], Boolean](BooleanTask(5), 1)
//     //   }
//     //   "math" in {
//     //     test[Refine[Reduction[String, Boolean]], Int](MathTask(5), 1)
//     //   }
//     // }
//     // "reduction + refine" - {
//     //   "boolean" in {
//     //     test[Refine[Reduction[String, Boolean]], Boolean](BooleanTask(5), 1)
//     //   }
//     //   "math" in {
//     //     test[Refine[Reduction[String, Boolean]], Int](MathTask(5), 1)
//     //   }
//     // }
//   }

//   // "large exprs" - {
//   //   "no refine" - {
//   //     "boolean" in {
//   //       test[Reduction[String, Boolean], Boolean](BooleanTask(15), 0.6)
//   //     }
//   //     "math" in {
//   //       test[Reduction[String, Boolean], Int](MathTask(15), 0.6)
//   //     }
//   //   }
//   //   "refine" - {
//   //     "boolean" in {
//   //       test[Refine[Reduction[String, Boolean]], Boolean](BooleanTask(15), 0.8)
//   //     }
//   //     "math" in {
//   //       test[Refine[Reduction[String, Boolean]], Int](MathTask(15), 0.8)
//   //     }
//   //   }
//   // }

//   object exprs {

//     sealed trait BooleanTask extends Task[Boolean]

//     object BooleanTask {
//       def apply(size: Int): BooleanTask =
//         if (size <= 1) {
//           Random.nextBoolean() match {
//             case true  => True
//             case false => False
//           }
//         } else {
//           Random.nextInt(3) match {
//             case 0 => And(apply(size / 2), apply(size / 2))
//             case 1 => Or(apply(size / 2), apply(size / 2))
//             case 2 => Not(apply(size - 1))
//           }
//         }

//       case object True extends BooleanTask {
//         def eval = true
//         def show = "true"
//       }
//       case object False extends BooleanTask {
//         def eval = false
//         def show = "false"
//       }
//       case class Not(expr: BooleanTask) extends BooleanTask {
//         def eval = !expr.eval
//         def show = s"!(${expr.show})"
//       }
//       case class And(left: BooleanTask, right: BooleanTask) extends BooleanTask {
//         def eval = left.eval && right.eval
//         def show = s"(${left.show} && ${right.show})"
//       }
//       case class Or(left: BooleanTask, right: BooleanTask) extends BooleanTask {
//         def eval = left.eval || right.eval
//         def show = s"(${left.show} || ${right.show})"
//       }
//     }

//     import scala.util.Random

//     sealed trait MathTask extends Task[Int]

//     object MathTask {
//       def apply(size: Int): MathTask =
//         if (size <= 1) {
//           Const(Random.nextInt(10))
//         } else {
//           Random.nextInt(3) match {
//             case 0 => Add(MathTask(size / 2), MathTask(size / 2))
//             case 1 => Subtract(MathTask(size / 2), MathTask(size / 2))
//             case 2 => Multiply(MathTask(size / 2), MathTask(size / 2))
//           }
//         }

//       case class Const(value: Int) extends MathTask {
//         def eval = value
//         def show = value.toString
//       }

//       case class Add(left: MathTask, right: MathTask) extends MathTask {
//         def eval = left.eval + right.eval
//         def show = s"(${left.show} + ${right.show})"
//       }

//       case class Subtract(left: MathTask, right: MathTask) extends MathTask {
//         def eval = left.eval - right.eval
//         def show = s"(${left.show} - ${right.show})"
//       }

//       case class Multiply(left: MathTask, right: MathTask) extends MathTask {
//         def eval = left.eval * right.eval
//         def show = s"(${left.show} * ${right.show})"
//       }
//     }
//   }
// }
