package kyo

import kyo.ios._

object test extends App {

  import embeds._

  // Calculate the factorial of 5 using Python
  val num = 5
  val pythonFactorial = IOs.run(python"""
  def factorial(n):
    return 1 if n == 0 else n * factorial(n-1)
  factorial(10)
  """)
  println(s"Factorial of $num calculated using Python is $pythonFactorial")

  // // Double the result using JavaScript
  // val jsDouble = js"2 * $pythonFactorial"
  // println(s"Double the factorial using JavaScript: $jsDouble")

  // // Add a suffix using Ruby
  // val rubyResult = ruby"'$jsDouble' + ' is the result'"
  // println(s"Appending string using Ruby: $rubyResult")

  // // Combine everything
  // println(s"Combined Result: $rubyResult")
}
