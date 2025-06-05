package com.example

import scala.scalanative.unsafe.*
// import scala.compiletime.uninitialized // Not needed if no fields
// import scala.scalanative.unsigned.* // Not needed if no unsigned fields

// Minimal CStruct
class MinimalFields extends CStruct:
  var field1: CInt = 0 // Initialize directly to avoid `uninitialized` for this test
end MinimalFields

@extern
object MinimalCApi:
  def test_func(ptr: Ptr[MinimalFields]): CInt = extern
end MinimalCApi

// object Main:
//   def main(args: Array[String]): Unit =
//     println("Minimal SN CStruct Test - Round 2")