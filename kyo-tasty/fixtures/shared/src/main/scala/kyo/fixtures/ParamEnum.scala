package kyo.fixtures

// Parametric enum: cases with constructor parameters.
// Used to verify Symbol.EnumCase (class-form) on JS and Native.
enum Shape:
    case Circle(radius: Double)
    case Square(side: Double)
    case Rectangle(w: Double, h: Double)
end Shape
