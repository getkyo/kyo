package kyo

class InstantTest extends Test:

    "parse" - {
        "valid ISO-8601 string" in {
            val result = Instant.parse("2023-05-20T12:34:56.789Z")
            assert(result.isSuccess)
            val instant = result.getOrThrow
            assert(instant.show == "2023-05-20T12:34:56.789Z")
        }

        "invalid string" in {
            val result = Instant.parse("invalid")
            assert(result.isFail)
        }

        "parse near Min" in {
            val result = Instant.parse("-1000000000-01-01T00:00:00Z")
            assert(result.isSuccess)
            assert(result.getOrThrow == Instant.Min)
        }

        "parse near Max" in {
            val result = Instant.parse("+1000000000-12-31T23:59:59.999999999Z")
            assert(result.isSuccess)
            assert(result.getOrThrow == Instant.Max)
        }
    }

    "+" - {
        "add zero duration" in {
            val instant = Instant.Epoch
            assert(instant + Duration.Zero == instant)
        }

        "add infinite duration" in {
            val instant = Instant.Epoch
            assert(instant + Duration.Infinity == Instant.Max)
        }

        "near Max" in {
            val nearMax = Instant.Max - 1.second
            assert((nearMax + 2.seconds) == Instant.Max)
        }
    }

    "-" - {
        "subtract duration" - {
            "subtract zero duration" in {
                val instant = Instant.Epoch
                assert(instant - Duration.Zero == instant)
            }

            "subtract infinite duration" in {
                val instant = Instant.Epoch
                assert(instant - Duration.Infinity == Instant.Min)
            }
        }

        "subtract instant" - {
            "same instant" in {
                val instant = Instant.Epoch
                assert((instant - instant) == Duration.Zero)
            }

            "later instant" in {
                val instant1 = Instant.Epoch
                val instant2 = instant1 + 1000.seconds
                assert((instant2 - instant1) == 1000.seconds)
            }

            "earlier instant" in {
                val instant1 = Instant.Epoch
                val instant2 = instant1 - 1000.seconds
                assert((instant2 - instant1) == Duration.Zero)
            }

            "very distant instants" in {
                assert((Instant.Max - Instant.Min) == Duration.Zero)
            }

            "large difference" in {
                val instant1 = Instant.parse("2023-01-01T00:00:00Z").getOrThrow
                val instant2 = Instant.parse("1970-01-01T00:00:00Z").getOrThrow
                val duration = instant1 - instant2
                assert(duration == 1672531200.seconds)
            }
        }

        "near Min" in {
            val nearMin = Instant.Min + 1.second
            assert((nearMin - 2.seconds) == Instant.Min)
        }
    }

    ">" - {
        "equal instants" in {
            val instant1 = Instant.Epoch
            val instant2 = Instant.Epoch
            assert(!(instant1 > instant2))
        }

        "earlier instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 + 1000.seconds
            assert(!(instant1 > instant2))
        }

        "later instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 - 1000.seconds
            assert(instant1 > instant2)
        }
    }

    "<" - {
        "equal instants" in {
            val instant1 = Instant.Epoch
            val instant2 = Instant.Epoch
            assert(!(instant1 < instant2))
        }

        "earlier instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 + 1000.seconds
            assert(instant1 < instant2)
        }

        "later instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 - 1000.seconds
            assert(!(instant1 < instant2))
        }
    }

    ">=" - {
        "equal instants" in {
            val instant1 = Instant.Epoch
            val instant2 = Instant.Epoch
            assert(instant1 >= instant2)
        }

        "earlier instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 + 1000.seconds
            assert(!(instant1 >= instant2))
        }

        "later instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 - 1000.seconds
            assert(instant1 >= instant2)
        }
    }

    "<=" - {
        "equal instants" in {
            val instant1 = Instant.Epoch
            val instant2 = Instant.Epoch
            assert(instant1 <= instant2)
        }

        "earlier instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 + 1000.seconds
            assert(instant1 <= instant2)
        }

        "later instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 - 1000.seconds
            assert(!(instant1 <= instant2))
        }
    }

    "truncatedTo" - {
        "seconds" in {
            val instant   = Instant.Min + 1000.seconds + 500.millis
            val truncated = instant.truncatedTo(Duration.Units.Seconds)
            assert(truncated == Instant.Min + 1000.seconds)
        }

        "minutes" in {
            val instant   = Instant.Min + 1000.seconds + 30.seconds
            val truncated = instant.truncatedTo(Duration.Units.Minutes)
            assert(truncated == Instant.Min + 1020.seconds)
        }

        "hours" in {
            val instant   = Instant.Min + 25.hours + 30.minutes
            val truncated = instant.truncatedTo(Duration.Units.Hours)
            assert(truncated == Instant.Min + 25.hours)
        }

        "days" in {
            val instant   = Instant.Min + 25.hours + 30.minutes
            val truncated = instant.truncatedTo(Duration.Units.Days)
            assert(truncated == Instant.Min + 1.day)
        }

        "unsupported unit" in {
            assertDoesNotCompile("instant.truncatedTo(Duration.Units.Weeks)")
            assertDoesNotCompile("instant.truncatedTo(Duration.Units.Months)")
        }
    }

    "of" - {
        "valid input" in {
            val instant = Instant.of(5.seconds, 500.millis)
            assert(instant.show == "1970-01-01T00:00:05.500Z")
        }

        "nanoseconds overflow" in {
            val instant = Instant.of(5.seconds, 1500.millis)
            assert(instant.show == "1970-01-01T00:00:06.500Z")
        }
    }

    "Ordering" - {
        "equal instants" in {
            val instant1 = Instant.Epoch
            val instant2 = Instant.Epoch
            assert(Ordering[Instant].compare(instant1, instant2) == 0)
        }

        "earlier instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 + 1000.seconds
            assert(Ordering[Instant].compare(instant1, instant2) < 0)
        }

        "later instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 - 1000.seconds
            assert(Ordering[Instant].compare(instant1, instant2) > 0)
        }

        "Min and Max" in {
            assert(Ordering[Instant].compare(Instant.Min, Instant.Max) < 0)
            assert(Ordering[Instant].compare(Instant.Max, Instant.Min) > 0)
        }

        "sorted list" in {
            val instants = List(
                Instant.Epoch,
                Instant.Epoch + 1000.seconds,
                Instant.Epoch - 1000.seconds,
                Instant.Max,
                Instant.Min
            )
            val sortedInstants = instants.sorted
            assert(sortedInstants == List(
                Instant.Min,
                Instant.Epoch - 1000.seconds,
                Instant.Epoch,
                Instant.Epoch + 1000.seconds,
                Instant.Max
            ))
        }
    }

    "java interop" - {

        "fromJava" - {
            "convert java.time.Instant to Instant" in {
                val javaInstant = java.time.Instant.parse("2023-05-20T12:34:56.789Z")
                val instant     = Instant.fromJava(javaInstant)
                assert(instant.show == "2023-05-20T12:34:56.789Z")
            }

            "convert java.time.Instant.MIN to Instant.Min" in {
                val javaInstant = java.time.Instant.MIN
                val instant     = Instant.fromJava(javaInstant)
                assert(instant == Instant.Min)
            }

            "convert java.time.Instant.MAX to Instant.Max" in {
                val javaInstant = java.time.Instant.MAX
                val instant     = Instant.fromJava(javaInstant)
                assert(instant == Instant.Max)
            }
        }

        "toJava" - {
            "convert Instant to java.time.Instant" in {
                val instant     = Instant.parse("2023-05-20T12:34:56.789Z").getOrThrow
                val javaInstant = instant.toJava
                assert(javaInstant.toString == "2023-05-20T12:34:56.789Z")
            }

            "roundtrip conversion" in {
                val original  = Instant.parse("2023-05-20T12:34:56.789Z").getOrThrow
                val roundtrip = Instant.fromJava(original.toJava)
                assert(original == roundtrip)
            }

            "convert Instant.Min to java.time.Instant.MIN" in {
                val javaInstant = Instant.Min.toJava
                assert(javaInstant eq java.time.Instant.MIN)
            }

            "convert Instant.Max to java.time.Instant.MAX" in {
                val javaInstant = Instant.Max.toJava
                assert(javaInstant eq java.time.Instant.MAX)
            }
        }
    }

    "min" - {
        "earlier instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 + 1000.seconds
            assert(instant1.min(instant2) == instant1)
        }

        "later instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 - 1000.seconds
            assert(instant1.min(instant2) == instant2)
        }

        "equal instants" in {
            val instant1 = Instant.Epoch
            val instant2 = Instant.Epoch
            assert(instant1.min(instant2) == instant1)
        }

        "with Min and Max" in {
            val instant = Instant.Epoch
            assert(instant.min(Instant.Min) == Instant.Min)
            assert(instant.min(Instant.Max) == instant)
        }
    }

    "max" - {
        "earlier instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 + 1000.seconds
            assert(instant1.max(instant2) == instant2)
        }

        "later instant" in {
            val instant1 = Instant.Epoch
            val instant2 = instant1 - 1000.seconds
            assert(instant1.max(instant2) == instant1)
        }

        "equal instants" in {
            val instant1 = Instant.Epoch
            val instant2 = Instant.Epoch
            assert(instant1.max(instant2) == instant1)
        }

        "with Min and Max" in {
            val instant = Instant.Epoch
            assert(instant.max(Instant.Min) == instant)
            assert(instant.max(Instant.Max) == Instant.Max)
        }
    }

    "between" - {
        "instant is between bounds" in {
            val start   = Instant.Epoch
            val end     = start + 1000.seconds
            val instant = start + 500.seconds
            assert(instant.between(start, end))
        }

        "instant equals start bound" in {
            val start = Instant.Epoch
            val end   = start + 1000.seconds
            assert(start.between(start, end))
        }

        "instant equals end bound" in {
            val start = Instant.Epoch
            val end   = start + 1000.seconds
            assert(end.between(start, end))
        }

        "instant before start bound" in {
            val start   = Instant.Epoch
            val end     = start + 1000.seconds
            val instant = start - 500.seconds
            assert(!instant.between(start, end))
        }

        "instant after end bound" in {
            val start   = Instant.Epoch
            val end     = start + 1000.seconds
            val instant = end + 500.seconds
            assert(!instant.between(start, end))
        }

        "with Min and Max" in {
            assert(Instant.Epoch.between(Instant.Min, Instant.Max))
            assert(Instant.Min.between(Instant.Min, Instant.Max))
            assert(Instant.Max.between(Instant.Min, Instant.Max))
        }
    }

    "clamp" - {
        "instant within bounds" in {
            val min     = Instant.Epoch
            val max     = min + 1000.seconds
            val instant = min + 500.seconds
            assert(instant.clamp(min, max) == instant)
        }

        "instant below min bound" in {
            val min     = Instant.Epoch
            val max     = min + 1000.seconds
            val instant = min - 500.seconds
            assert(instant.clamp(min, max) == min)
        }

        "instant above max bound" in {
            val min     = Instant.Epoch
            val max     = min + 1000.seconds
            val instant = max + 500.seconds
            assert(instant.clamp(min, max) == max)
        }

        "instant equals min bound" in {
            val min = Instant.Epoch
            val max = min + 1000.seconds
            assert(min.clamp(min, max) == min)
        }

        "instant equals max bound" in {
            val min = Instant.Epoch
            val max = min + 1000.seconds
            assert(max.clamp(min, max) == max)
        }

        "with Min and Max" in {
            assert(Instant.Epoch.clamp(Instant.Min, Instant.Max) == Instant.Epoch)
            assert(Instant.Min.clamp(Instant.Min, Instant.Max) == Instant.Min)
            assert(Instant.Max.clamp(Instant.Min, Instant.Max) == Instant.Max)
        }
    }

    "toDuration" - {
        "from Epoch" in {
            val instant = Instant.Epoch
            assert(instant.toDuration == Duration.Zero)
        }

        "positive duration" in {
            val instant = Instant.Epoch + 1000.seconds
            assert(instant.toDuration == 1000.seconds)
        }

        "negative duration" in {
            val instant = Instant.Epoch - 1000.seconds
            assert(instant.toDuration == Duration.Zero)
        }

        "with nanoseconds" in {
            val instant = Instant.Epoch + 1000.seconds + 500.nanos
            assert(instant.toDuration == 1000.seconds + 500.nanos)
        }

        "Max instant" in {
            assert(Instant.Max.toDuration == Duration.Infinity)
        }

        "Min instant" in {
            assert(Instant.Min.toDuration == Duration.Zero)
        }
    }

end InstantTest
