package kyobench

object Baseline:

    final case class Rec0(a: Int, b: String, c: List[Int])
    def make0(n: Int): Rec0 = Rec0(n, n.toString, List.tabulate(n % 8)(identity))
    def fold0(xs: List[Rec0]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec1(a: Int, b: String, c: List[Int])
    def make1(n: Int): Rec1 = Rec1(n, n.toString, List.tabulate(n % 8)(identity))
    def fold1(xs: List[Rec1]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec2(a: Int, b: String, c: List[Int])
    def make2(n: Int): Rec2 = Rec2(n, n.toString, List.tabulate(n % 8)(identity))
    def fold2(xs: List[Rec2]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec3(a: Int, b: String, c: List[Int])
    def make3(n: Int): Rec3 = Rec3(n, n.toString, List.tabulate(n % 8)(identity))
    def fold3(xs: List[Rec3]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec4(a: Int, b: String, c: List[Int])
    def make4(n: Int): Rec4 = Rec4(n, n.toString, List.tabulate(n % 8)(identity))
    def fold4(xs: List[Rec4]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec5(a: Int, b: String, c: List[Int])
    def make5(n: Int): Rec5 = Rec5(n, n.toString, List.tabulate(n % 8)(identity))
    def fold5(xs: List[Rec5]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec6(a: Int, b: String, c: List[Int])
    def make6(n: Int): Rec6 = Rec6(n, n.toString, List.tabulate(n % 8)(identity))
    def fold6(xs: List[Rec6]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec7(a: Int, b: String, c: List[Int])
    def make7(n: Int): Rec7 = Rec7(n, n.toString, List.tabulate(n % 8)(identity))
    def fold7(xs: List[Rec7]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec8(a: Int, b: String, c: List[Int])
    def make8(n: Int): Rec8 = Rec8(n, n.toString, List.tabulate(n % 8)(identity))
    def fold8(xs: List[Rec8]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec9(a: Int, b: String, c: List[Int])
    def make9(n: Int): Rec9 = Rec9(n, n.toString, List.tabulate(n % 8)(identity))
    def fold9(xs: List[Rec9]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec10(a: Int, b: String, c: List[Int])
    def make10(n: Int): Rec10 = Rec10(n, n.toString, List.tabulate(n % 8)(identity))
    def fold10(xs: List[Rec10]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec11(a: Int, b: String, c: List[Int])
    def make11(n: Int): Rec11 = Rec11(n, n.toString, List.tabulate(n % 8)(identity))
    def fold11(xs: List[Rec11]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec12(a: Int, b: String, c: List[Int])
    def make12(n: Int): Rec12 = Rec12(n, n.toString, List.tabulate(n % 8)(identity))
    def fold12(xs: List[Rec12]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec13(a: Int, b: String, c: List[Int])
    def make13(n: Int): Rec13 = Rec13(n, n.toString, List.tabulate(n % 8)(identity))
    def fold13(xs: List[Rec13]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec14(a: Int, b: String, c: List[Int])
    def make14(n: Int): Rec14 = Rec14(n, n.toString, List.tabulate(n % 8)(identity))
    def fold14(xs: List[Rec14]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec15(a: Int, b: String, c: List[Int])
    def make15(n: Int): Rec15 = Rec15(n, n.toString, List.tabulate(n % 8)(identity))
    def fold15(xs: List[Rec15]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec16(a: Int, b: String, c: List[Int])
    def make16(n: Int): Rec16 = Rec16(n, n.toString, List.tabulate(n % 8)(identity))
    def fold16(xs: List[Rec16]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec17(a: Int, b: String, c: List[Int])
    def make17(n: Int): Rec17 = Rec17(n, n.toString, List.tabulate(n % 8)(identity))
    def fold17(xs: List[Rec17]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec18(a: Int, b: String, c: List[Int])
    def make18(n: Int): Rec18 = Rec18(n, n.toString, List.tabulate(n % 8)(identity))
    def fold18(xs: List[Rec18]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

    final case class Rec19(a: Int, b: String, c: List[Int])
    def make19(n: Int): Rec19 = Rec19(n, n.toString, List.tabulate(n % 8)(identity))
    def fold19(xs: List[Rec19]): Int =
        xs.foldLeft(0) { (acc, r) =>
            r.c match
                case Nil          => acc + r.a
                case head :: tail => acc + head + tail.sum + r.b.length
        }

end Baseline
