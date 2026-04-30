package kyo

/** Sealed exception hierarchy for kyo-config flag errors.
  *
  * All exceptions thrown by flag infrastructure are subtypes of FlagException, enabling structured catch blocks and exhaustive pattern
  * matching on error categories.
  *
  * Category traits:
  *   - [[FlagParseException]] -- expression or value parsing failures
  *   - [[FlagValidationException]] -- validation function failures
  *   - [[FlagRegistrationException]] -- registration/name issues
  */
sealed abstract class FlagException(message: String, cause: Throwable)
    extends Exception(message, cause) {
    def this(message: String) = this(message, null)
}

// --- Category traits ---

/** Parse errors: value parsing, rollout expression parsing, or expression structure errors. */
sealed trait FlagParseException

/** Validation errors: the user-supplied validate function rejected a value. */
sealed trait FlagValidationException

/** Registration errors: flag name is invalid or already taken. */
sealed trait FlagRegistrationException

// --- Concrete subclasses ---

/** A flag value could not be parsed into the expected type.
  *
  * @param flagName
  *   fully-qualified flag name
  * @param value
  *   the raw string that failed to parse
  * @param typeName
  *   human-readable type name (e.g. "Int", "Boolean")
  * @param cause
  *   the underlying parse exception
  */
case class FlagValueParseException(
    flagName: String,
    value: String,
    typeName: String,
    cause: Throwable
) extends FlagException(
        s"Failed to parse flag '$flagName' ($typeName) value '$value': ${if (cause ne null) cause.getMessage else "unknown error"}",
        cause
    ) with FlagParseException

/** A rollout expression returned an error from Rollout.select.
  *
  * @param flagName
  *   fully-qualified flag name
  * @param expression
  *   the full rollout expression
  * @param detail
  *   the error detail from Rollout.select
  */
case class FlagRolloutParseException(
    flagName: String,
    expression: String,
    detail: String
) extends FlagException(
        s"Flag '$flagName' rollout expression error in '$expression': $detail"
    ) with FlagParseException

/** A DynamicFlag expression failed structural parsing (empty choice, empty selector, bad percentage, etc).
  *
  * @param flagName
  *   fully-qualified flag name
  * @param expression
  *   the full expression string
  * @param choiceIndex
  *   1-based choice index where the error occurred, or 0 if not choice-specific
  * @param detail
  *   human-readable detail about what went wrong
  */
case class FlagExpressionParseException(
    flagName: String,
    expression: String,
    choiceIndex: Int,
    detail: String
) extends FlagException(
        if (choiceIndex > 0)
            s"Flag '$flagName' expression parse error at choice $choiceIndex in '$expression': $detail"
        else
            s"Flag '$flagName' expression parse error in '$expression': $detail"
    ) with FlagParseException

/** A DynamicFlag value could not be parsed within a rollout expression choice.
  *
  * @param flagName
  *   fully-qualified flag name
  * @param expression
  *   the full expression string
  * @param choiceNum
  *   1-based choice number
  * @param totalChoices
  *   total number of choices in the expression
  * @param value
  *   the raw value string that failed
  * @param typeName
  *   human-readable type name
  * @param cause
  *   the underlying parse exception
  */
case class FlagChoiceParseException(
    flagName: String,
    expression: String,
    choiceNum: Int,
    totalChoices: Int,
    value: String,
    typeName: String,
    cause: Throwable
) extends FlagException(
        s"Flag '$flagName': failed to parse choice $choiceNum of $totalChoices " +
            s"in expression '$expression' -- value '$value' is not a valid " +
            s"$typeName: ${if (cause ne null) cause.getMessage else "unknown error"}",
        cause
    ) with FlagParseException

/** A flag's validation function rejected a value.
  *
  * @param flagName
  *   fully-qualified flag name
  * @param value
  *   string representation of the value that failed validation
  * @param source
  *   the config source that provided the value
  * @param cause
  *   the exception thrown by the validate function
  */
case class FlagValidationFailedException(
    flagName: String,
    value: String,
    source: String,
    cause: Throwable
) extends FlagException(
        s"Flag '$flagName' validation failed for value '$value' from $source: ${if (cause ne null) cause.getMessage else "unknown error"}",
        cause
    ) with FlagValidationException

/** A flag name is invalid (e.g. declared inside a class, trait, or method).
  *
  * @param flagName
  *   the invalid flag name (as derived from the JVM class name)
  * @param detail
  *   explanation of what is wrong
  */
case class FlagNameException(
    flagName: String,
    detail: String
) extends FlagException(
        s"Flag '$flagName' $detail"
    ) with FlagRegistrationException

/** A flag with the same name is already registered.
  *
  * @param flagName
  *   the duplicate flag name
  * @param newClassName
  *   JVM class name of the new flag attempting to register
  * @param existingClassName
  *   JVM class name of the already-registered flag
  */
case class FlagDuplicateNameException(
    flagName: String,
    newClassName: String,
    existingClassName: String
) extends FlagException(
        s"Duplicate flag name '$flagName': $newClassName conflicts with $existingClassName. Each flag name must be unique."
    ) with FlagRegistrationException
