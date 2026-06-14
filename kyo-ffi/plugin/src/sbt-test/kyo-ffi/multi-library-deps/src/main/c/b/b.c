// b depends on a's HEADER for its types/declarations. a's symbols are not
// linked in at this layer (b.c re-implements its own arithmetic). The header
// inclusion is enough to require the topo-sort: if a is compiled after b the
// header may still be present (it's source-only), but in real-world cases
// where a generates a header at compile time, the order matters. This fixture
// asserts the ORDER property via assertCcOrder; we keep b.c free of cross-lib
// link dependencies so the scripted test runs in vanilla CI without library
// search paths.
#include "a.h"
#include "b.h"
int b_quadruple(int x) { return x * 4; }
