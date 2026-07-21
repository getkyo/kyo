package kyo

/** Pluggable naming convention for mapping Scala type and field names to SQL table and column names.
  *
  * Two built-in implementations are provided: [[NamingStrategy.identity]] and [[NamingStrategy.snakeCase]]. Custom implementations may be
  * defined inline as `new NamingStrategy { ... }` or as objects extending the trait.
  */
trait NamingStrategy:
    def tableName(typeName: String): String
    def columnName(fieldName: String): String

object NamingStrategy:

    /** Pass-through. Type and field names are emitted verbatim. */
    case object identity extends NamingStrategy:
        def tableName(s: String): String  = s
        def columnName(s: String): String = s

    /** `Country` becomes `country`; `countryCode` becomes `country_code`; `topLevelCategoryId` becomes `top_level_category_id`. */
    case object snakeCase extends NamingStrategy:
        def tableName(s: String): String  = camelToSnake(s)
        def columnName(s: String): String = camelToSnake(s)

    private def camelToSnake(s: String): String =
        s.foldLeft(new StringBuilder) { (acc, c) =>
            if c.isUpper && acc.nonEmpty then acc.append('_')
            acc.append(c.toLower)
        }.toString

end NamingStrategy
