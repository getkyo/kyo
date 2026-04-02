# Phase 1b: Wire Exchange into HttpTransportClient

## Design requirement
"Exchange: uniform exchange(request) interface for both HTTP/1.1 and HTTP/2. 
Connection pool stores Exchanges — protocol version invisible to callers."

## Rules
- ZERO nulls, use Maybe
- Loop not while, Maybe not Option
- AllowUnsafe as implicit parameter, not embrace.danger
- Read CONTRIBUTING.md for all conventions
