package kyo.bench

import org.openjdk.jmh.annotations.Benchmark

class TagsBench extends Bench(()):

    class Super
    class Sub1 extends Super
    class Sub2 extends Super
    class Test1[+A]
    class Test2[-A]
    class Test3[A, B]
    class Test4[-A, -B]
    class Test5[+A, B]
    class Test6[T1, T2, T3, T4, T5]

    @Benchmark
    def syncKyo() =
        import kyo.*
        Tag[String] =:= Tag[String]
        Tag[Int] <:< Tag[Any]
        Tag[Sub1] <:< Tag[Super]
        Tag[Super] <:< Tag[Sub2]
        Tag[Test1[Sub1]] <:< Tag[Test1[Super]]
        Tag[Test2[Super]] <:< Tag[Test2[Sub1]]
        Tag[Test3[String, Int]] =:= Tag[Test3[String, Int]]
        Tag[Test4[Super, Super]] <:< Tag[Test4[Sub1, Sub2]]
        Tag[Test5[Sub1, String]] <:< Tag[Test5[Super, String]]
        Tag[Test6[Int, String, Boolean, Double, Char]] =:= Tag[Test6[Int, String, Boolean, Double, Char]]
        Tag[Test3[Test1[Sub1], Test2[Super]]] <:< Tag[Test3[Test1[Super], Test2[Sub1]]]
        Tag[Test6[Test4[Super, Super], Test5[Sub1, String], Int, String, Boolean]] =:=
            Tag[Test6[Test4[Super, Super], Test5[Sub1, String], Int, String, Boolean]]
    end syncKyo

    @Benchmark
    def syncIzumi() =
        import izumi.reflect.*
        Tag[String] =:= Tag[String]
        Tag[Int] <:< Tag[Any]
        Tag[Sub1] <:< Tag[Super]
        Tag[Super] <:< Tag[Sub2]
        Tag[Test1[Sub1]] <:< Tag[Test1[Super]]
        Tag[Test2[Super]] <:< Tag[Test2[Sub1]]
        Tag[Test3[String, Int]] =:= Tag[Test3[String, Int]]
        Tag[Test4[Super, Super]] <:< Tag[Test4[Sub1, Sub2]]
        Tag[Test5[Sub1, String]] <:< Tag[Test5[Super, String]]
        Tag[Test6[Int, String, Boolean, Double, Char]] =:= Tag[Test6[Int, String, Boolean, Double, Char]]
        Tag[Test3[Test1[Sub1], Test2[Super]]] <:< Tag[Test3[Test1[Super], Test2[Sub1]]]
        Tag[Test6[Test4[Super, Super], Test5[Sub1, String], Int, String, Boolean]] =:=
            Tag[Test6[Test4[Super, Super], Test5[Sub1, String], Int, String, Boolean]]
    end syncIzumi

end TagsBench
