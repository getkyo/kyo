# STEERING — STOP ANALYZING, COMPILE AND TEST

The 3-4 WS failures are likely flaky under stress on Native. Run the tests 2 more times to confirm. If the same tests fail consistently, fix them. If different tests fail each time, they're flaky stress tests — report the findings and move on.

Do NOT spend more than 1 minute analyzing each failure. Try a fix, compile, test.
