package kyo

import scala.annotation.*
import scala.quoted.*
import scala.util.NotGiven

case class Field[Name <: String, Value](name: String, value: Value)

infix type ~>[Name <: String, Value] = Field[Name, Value]

extension [Name <: String](self: Name)
    def ~>[Value](value: Value): self.type ~> Value = Field(self, value)

opaque type Record[-Fields] = Map[String, Any]

object Record:
    def init: Record[Nothing] = Map.empty

    extension [Fields](self: Record[Fields])
        def toMap: Map[String, Any] = self

        def apply[Name <: String, Value](field: Name ~> Value): Record[Fields | Name ~> Value] =
            self.updated(field.name, field.value)

        def apply[Name <: String, Value](name: Name)(using name.type ~> Value <:< Fields): Value =
            self(name).asInstanceOf[Value]

        def ++[Fields2](other: Record[Fields2]): Record[Fields | Fields2] =
            self ++ other
    end extension

    private def addFieldMacro[Fields: Type, Name <: String: Type, Value: Type](
        self: Expr[Record[Fields]],
        field: Expr[Field[Name, Value]]
    )(using Quotes): Expr[Any] =
        import quotes.reflect.*
        // val name = nameExpr.con
        val fieldsType = TypeRepr.of[Fields]

        def valueTypes(tpe: TypeRepr): List[TypeRepr] =
            tpe match
                case OrType(left, right) =>
                    valueTypes(left) ++ valueTypes(right)
                case tpe @ AppliedType(_, List(name, value)) if tpe <:< TypeRepr.of[Field[?, ?]] =>
                    List(value)
                case _ =>
                    println(tpe)
                    ???

        val valueType = valueTypes(fieldsType)

        println(valueType)

        // valueType.asType match
        //     case '[t] => '{ $self($name).asInstanceOf[t] }
        ???
    end getFieldMacro

end Record
