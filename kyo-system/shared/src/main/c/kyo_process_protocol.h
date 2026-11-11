#ifndef KYO_PROCESS_PROTOCOL_H
#define KYO_PROCESS_PROTOCOL_H

#include <stdint.h>

enum kyo_process_request {
    KYO_PROCESS_TERMINATE = 1,
    KYO_PROCESS_KILL = 2,
    KYO_PROCESS_RELEASE = 3,
    KYO_PROCESS_POLL = 4
};

enum kyo_process_event_kind {
    KYO_PROCESS_READY = 1,
    KYO_PROCESS_EXITED = 2,
    KYO_PROCESS_SIGNALED = 3,
    KYO_PROCESS_RUNNING = 4,
    KYO_PROCESS_FAILED = 5,
    KYO_PROCESS_RELEASED = 6
};

/* Both endpoints run on the same host and use this header. Standard streams carry only command data. */
struct kyo_process_event {
    int32_t kind;
    int32_t value;
};

#endif
