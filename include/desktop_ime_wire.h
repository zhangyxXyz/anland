#ifndef ANLAND_DESKTOP_IME_WIRE_H
#define ANLAND_DESKTOP_IME_WIRE_H
#include <stdint.h>

/* One request/reply per AF_UNIX SOCK_SEQPACKET connection. Both ends are
 * little-endian ARM64. Text is bounded UTF-8, never logged. Ops 1..6 match
 * AWL_IME_*, 0 reads the current editor. A focus generation rejects stale
 * commits instead of typing into a newly focused application. */
#define ANLAND_IME_MAGIC 0x41494d31u
#define ANLAND_IME_TEXT_MAX 4000u
#define ANLAND_IME_SURROUNDING 1u
#define ANLAND_IME_PASSWORD 2u
struct anland_ime_request {
    uint32_t magic, op;
    uint64_t context;
    int32_t a, b;
    uint32_t length, reserved;
};
struct anland_ime_reply {
    uint32_t magic;
    int32_t status; /* 1 focused, 2 no editor, -1 rejected/unavailable */
    uint64_t context;
    uint32_t flags;
    int32_t cursor, anchor, x, y, width, height;
    uint32_t length;
};
#endif
