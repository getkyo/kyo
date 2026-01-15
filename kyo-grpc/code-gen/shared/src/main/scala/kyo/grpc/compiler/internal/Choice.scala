package kyo.grpc.compiler.internal

import scala.language.implicitConversions

private[compiler] trait Choice { self =>

    type A

    type Choose = self.type => A

    implicit def makeChoice(choose: Choose): A = choose(self)

    implicit final class ChooseOps(choose: Choose) {
        def choice: A = choose(self)
    }

    implicit final class ChoiceOps(choice: A) {
        def choose: Choose = _ => choice
    }
}
