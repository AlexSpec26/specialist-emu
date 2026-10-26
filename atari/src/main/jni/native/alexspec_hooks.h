/*
 * Hooks compiled into the Atari800 CPU core (vendor/atari800/atari800/src/cpu.c, built with
 * -DALEXSPEC_HOOKS). They give the app what the Specialist and ZX emulators have:
 *  - a memory map: for every byte, the emulated time of the last read, write and execution
 *    (opcode or operand fetch) by the CPU;
 *  - stopping between two instructions anywhere inside a frame (single steps, slow motion),
 *    see timeline.c.
 * The definitions live in trace.c. GPL-2.0-or-later, see atari/COPYING.
 */
#ifndef ALEXSPEC_HOOKS_H
#define ALEXSPEC_HOOKS_H

#include "atari.h"
#include "memory.h"

/* Non-zero while the instruction hook must run (trace on or a stop requested). */
extern int ALEX_hooks;
/* Non-zero while accesses are recorded. */
extern int ALEX_trace;
/* Time stamp given to the accesses of the current instruction: its start time + 1. */
extern long long ALEX_stamp;
extern long long ALEX_last_read[65536], ALEX_last_write[65536], ALEX_last_exec[65536];

/* Called before every instruction; may longjmp out of the CPU (see timeline.c). */
void ALEX_instruction(unsigned pc, unsigned a, unsigned x, unsigned y, unsigned s, unsigned p);

#define ALEX_MARK_READ(addr)  do { if (ALEX_trace) ALEX_last_read[(UWORD) (addr)] = ALEX_stamp; } while (0)
#define ALEX_MARK_WRITE(addr) do { if (ALEX_trace) ALEX_last_write[(UWORD) (addr)] = ALEX_stamp; } while (0)
#define ALEX_MARK_EXEC(addr)  do { if (ALEX_trace) ALEX_last_exec[(UWORD) (addr)] = ALEX_stamp; } while (0)

static inline UBYTE ALEX_read(UWORD a)
{
	ALEX_MARK_READ(a);
	return MEMORY_mem[a];
}

static inline UBYTE ALEX_write(UWORD a, UBYTE v)
{
	ALEX_MARK_WRITE(a);
	return MEMORY_mem[a] = v;
}

static inline UWORD ALEX_read_word(UWORD a)
{
	ALEX_MARK_READ(a);
	ALEX_MARK_READ(a + 1);
	return (UWORD) (MEMORY_mem[a] | (MEMORY_mem[(UWORD) (a + 1)] << 8));
}

/* Reads that may hit chip registers (D000-D7FF) */
static inline UBYTE ALEX_get(UWORD a)
{
	ALEX_MARK_READ(a);
	return MEMORY_attrib[a] == MEMORY_HARDWARE ? MEMORY_HwGetByte(a, FALSE) : MEMORY_mem[a];
}

static inline void ALEX_put(UWORD a, UBYTE v)
{
	ALEX_MARK_WRITE(a);
	if (MEMORY_attrib[a] == MEMORY_RAM) MEMORY_mem[a] = v;
	else if (MEMORY_attrib[a] == MEMORY_HARDWARE) MEMORY_HwPutByte(a, v);
}

static inline UBYTE ALEX_code(UWORD a)
{
	ALEX_MARK_EXEC(a);
	return MEMORY_mem[a];
}

static inline UWORD ALEX_code_word(UWORD a)
{
	ALEX_MARK_EXEC(a);
	ALEX_MARK_EXEC(a + 1);
	return (UWORD) (MEMORY_mem[a] | (MEMORY_mem[(UWORD) (a + 1)] << 8));
}

#endif
