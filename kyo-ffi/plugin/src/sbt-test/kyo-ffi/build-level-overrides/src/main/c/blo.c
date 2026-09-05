/* The build-level append is asserted by the compiler itself: without the macro the
   flags never reached the compile, and this translation unit refuses to build. */
#ifndef KYO_FFI_BUILD_LEVEL
#error "ThisBuild / ffiCFlags did not reach the compiler"
#endif

int blo_answer(void) { return 42; }
