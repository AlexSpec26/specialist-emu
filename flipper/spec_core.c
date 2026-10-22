/*
 * ПК «Специалист» emulation core: Intel 8080 (КР580ВМ80А) + memory + PPI keyboard/beeper.
 * Ported from the Android version (I8080.java / Specialist.java); passes the same CPU tests.
 */
#if defined(__GNUC__) && !defined(__clang__)
#pragma GCC optimize("O2")
#endif

#include "spec_core.h"

#include <string.h>

/* io_base / ram_top let the CPU tests run on a flat 64 KB RAM (desktop test build only);
 * on the device they are compile-time constants so the memory fast path stays cheap. */
#ifdef SPEC_TEST_BUILD
static uint32_t io_base = 0xF000;
static uint32_t ram_top = 0xC000;

void spec_core_flat_memory(bool flat) {
    io_base = flat ? 0x10000 : 0xF000;
    ram_top = flat ? 0x10000 : 0xC000;
}
#else
#define io_base 0xF000u
#define ram_top 0xC000u
void spec_core_flat_memory(bool flat) {
    (void)flat;
}
#endif

static const uint8_t CYCLES[256] = {
    4, 10, 7, 5, 5, 5, 7, 4, 4, 10, 7, 5, 5, 5, 7, 4,
    4, 10, 7, 5, 5, 5, 7, 4, 4, 10, 7, 5, 5, 5, 7, 4,
    4, 10, 16, 5, 5, 5, 7, 4, 4, 10, 16, 5, 5, 5, 7, 4,
    4, 10, 13, 5, 10, 10, 10, 4, 4, 10, 13, 5, 5, 5, 7, 4,
    5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,
    5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,
    5, 5, 5, 5, 5, 5, 7, 5, 5, 5, 5, 5, 5, 5, 7, 5,
    7, 7, 7, 7, 7, 7, 7, 7, 5, 5, 5, 5, 5, 5, 7, 5,
    4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,
    4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,
    4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,
    4, 4, 4, 4, 4, 4, 7, 4, 4, 4, 4, 4, 4, 4, 7, 4,
    5, 10, 10, 10, 11, 11, 7, 11, 5, 10, 10, 10, 11, 17, 7, 11,
    5, 10, 10, 10, 11, 11, 7, 11, 5, 10, 10, 10, 11, 17, 7, 11,
    5, 10, 10, 18, 11, 11, 7, 11, 5, 5, 10, 4, 11, 17, 7, 11,
    5, 10, 10, 4, 11, 11, 7, 11, 5, 5, 10, 4, 11, 17, 7, 11,
};

static uint8_t PARITY[256];
static bool tables_ready;

static void init_tables(void) {
    if(tables_ready) return;
    for(int i = 0; i < 256; i++) {
        int b = 0;
        for(int k = 0; k < 8; k++) b += (i >> k) & 1;
        PARITY[i] = (b & 1) == 0;
    }
    tables_ready = true;
}

/* ------------------------------------------------------------------ PPI */

static uint16_t selected_columns(const Spec* m) {
    uint16_t a = m->a_in ? 0xFF : m->port_a;
    uint16_t c = m->clo_in ? 0x0F : (m->port_c & 0x0F);
    return (uint16_t)(~(a | (c << 8)) & 0xFFF);
}

static uint8_t rows_for_columns(const Spec* m, uint16_t cols) {
    uint8_t rows = 0;
    for(int j = 0; j < SPEC_COLS; j++)
        if(cols & (1u << j)) rows |= m->key_cols[j];
    return rows;
}

static uint16_t columns_for_rows(const Spec* m, uint8_t rows) {
    uint16_t cols = 0;
    if(!rows) return 0;
    for(int j = 0; j < SPEC_COLS; j++)
        if(m->key_cols[j] & rows) cols |= (uint16_t)(1u << j);
    return cols;
}

static void apply_port_c(Spec* m) {
    uint8_t eff = m->chi_in ? (m->port_c | 0xF0) : m->port_c;
    bool nb = (eff & 0x20) != 0;
    if(nb != m->beeper) m->beeper_toggles++;
    m->beeper = nb;
}

static uint8_t ppi_read(Spec* m, int reg) {
    switch(reg) {
    case 0: {
        if(!m->a_in) return m->port_a;
        uint8_t rows = m->b_in ? 0 : (uint8_t)((~(m->port_b >> 2)) & 0x3F);
        return (uint8_t)(~columns_for_rows(m, rows) & 0xFF);
    }
    case 1: {
        if(!m->b_in) return m->port_b;
        uint8_t rows = rows_for_columns(m, selected_columns(m));
        return (uint8_t)(((~rows & 0x3F) << 2) | (m->shift ? 0 : 2) | 1);
    }
    case 2: {
        uint8_t v = m->chi_in ? 0xF0 : (m->port_c & 0xF0);
        if(m->clo_in) {
            uint8_t rows = m->b_in ? 0 : (uint8_t)((~(m->port_b >> 2)) & 0x3F);
            v |= (uint8_t)((~columns_for_rows(m, rows) >> 8) & 0x0F);
        } else {
            v |= m->port_c & 0x0F;
        }
        return v;
    }
    default:
        return 0xFF;
    }
}

static void ppi_write(Spec* m, int reg, uint8_t v) {
    switch(reg) {
    case 0: m->port_a = v; break;
    case 1: m->port_b = v; break;
    case 2: m->port_c = v; apply_port_c(m); break;
    default:
        if(v & 0x80) {
            m->a_in = (v & 0x10) != 0;
            m->chi_in = (v & 0x08) != 0;
            m->b_in = (v & 0x02) != 0;
            m->clo_in = (v & 0x01) != 0;
            m->port_a = m->port_b = m->port_c = 0;
        } else {
            int bit = (v >> 1) & 7;
            if(v & 1) m->port_c |= (uint8_t)(1u << bit);
            else m->port_c &= (uint8_t)~(1u << bit);
        }
        apply_port_c(m);
        break;
    }
}

/* ------------------------------------------------------------------ bus */

static inline uint8_t rd(Spec* m, uint16_t addr) {
    if(addr < io_base) return m->mem[addr];
    if(addr >= 0xF800) return ppi_read(m, addr & 3);
    return 0xFF;
}

static inline void wr(Spec* m, uint16_t addr, uint8_t v) {
    if(addr < ram_top) {
        m->mem[addr] = v;
        if(addr >= 0x9000) m->last_vwrite = addr;
    } else if(addr >= 0xF800 && ram_top != 0x10000) {
        ppi_write(m, addr & 3, v);
    }
}

/* ------------------------------------------------------------------ CPU */

#define C (&m->cpu)

static inline uint8_t fetch(Spec* m) {
    return rd(m, C->pc++);
}

static inline uint16_t fetch16(Spec* m) {
    uint8_t lo = fetch(m);
    return (uint16_t)(lo | (fetch(m) << 8));
}

static inline uint16_t rd16(Spec* m, uint16_t a) {
    return (uint16_t)(rd(m, a) | (rd(m, (uint16_t)(a + 1)) << 8));
}

static inline void wr16(Spec* m, uint16_t a, uint16_t v) {
    wr(m, a, (uint8_t)v);
    wr(m, (uint16_t)(a + 1), (uint8_t)(v >> 8));
}

static inline void push(Spec* m, uint16_t v) {
    C->sp -= 2;
    wr16(m, C->sp, v);
}

static inline uint16_t pop(Spec* m) {
    uint16_t v = rd16(m, C->sp);
    C->sp += 2;
    return v;
}

#define BC ((uint16_t)((C->b << 8) | C->c))
#define DE ((uint16_t)((C->d << 8) | C->e))
#define HL ((uint16_t)((C->h << 8) | C->l))

static inline void set_bc(Spec* m, uint16_t v) { C->b = (uint8_t)(v >> 8); C->c = (uint8_t)v; }
static inline void set_de(Spec* m, uint16_t v) { C->d = (uint8_t)(v >> 8); C->e = (uint8_t)v; }
static inline void set_hl(Spec* m, uint16_t v) { C->h = (uint8_t)(v >> 8); C->l = (uint8_t)v; }

static inline uint8_t get_f(Spec* m) {
    return (uint8_t)((C->fs ? 0x80 : 0) | (C->fz ? 0x40 : 0) | (C->fac ? 0x10 : 0) | (C->fp ? 0x04 : 0) | 0x02 |
                     (C->fcy ? 1 : 0));
}

static inline void set_f(Spec* m, uint8_t f) {
    C->fs = (f & 0x80) != 0;
    C->fz = (f & 0x40) != 0;
    C->fac = (f & 0x10) != 0;
    C->fp = (f & 0x04) != 0;
    C->fcy = (f & 0x01) != 0;
}

static inline void szp(Spec* m, uint8_t v) {
    C->fs = (v & 0x80) != 0;
    C->fz = v == 0;
    C->fp = PARITY[v];
}

static inline uint8_t get_reg(Spec* m, int r) {
    switch(r) {
    case 0: return C->b;
    case 1: return C->c;
    case 2: return C->d;
    case 3: return C->e;
    case 4: return C->h;
    case 5: return C->l;
    case 6: return rd(m, HL);
    default: return C->a;
    }
}

static inline void set_reg(Spec* m, int r, uint8_t v) {
    switch(r) {
    case 0: C->b = v; break;
    case 1: C->c = v; break;
    case 2: C->d = v; break;
    case 3: C->e = v; break;
    case 4: C->h = v; break;
    case 5: C->l = v; break;
    case 6: wr(m, HL, v); break;
    default: C->a = v; break;
    }
}

static inline void op_add(Spec* m, uint8_t v, int carry) {
    uint16_t res = (uint16_t)(C->a + v + carry);
    C->fac = ((C->a ^ v ^ res) & 0x10) != 0;
    C->fcy = (res & 0x100) != 0;
    C->a = (uint8_t)res;
    szp(m, C->a);
}

static inline uint8_t op_sub(Spec* m, uint8_t v, int borrow) {
    uint8_t nv = (uint8_t)~v;
    uint16_t res = (uint16_t)(C->a + nv + (borrow ^ 1));
    C->fac = ((C->a ^ nv ^ res) & 0x10) != 0;
    C->fcy = (res & 0x100) == 0;
    szp(m, (uint8_t)res);
    return (uint8_t)res;
}

static inline void op_ana(Spec* m, uint8_t v) {
    C->fac = ((C->a | v) & 0x08) != 0;
    C->a &= v;
    C->fcy = 0;
    szp(m, C->a);
}

static inline void op_xra(Spec* m, uint8_t v) {
    C->a ^= v;
    C->fac = 0;
    C->fcy = 0;
    szp(m, C->a);
}

static inline void op_ora(Spec* m, uint8_t v) {
    C->a |= v;
    C->fac = 0;
    C->fcy = 0;
    szp(m, C->a);
}

static inline void alu(Spec* m, int op, uint8_t v) {
    switch(op) {
    case 0: op_add(m, v, 0); break;
    case 1: op_add(m, v, C->fcy); break;
    case 2: C->a = op_sub(m, v, 0); break;
    case 3: C->a = op_sub(m, v, C->fcy); break;
    case 4: op_ana(m, v); break;
    case 5: op_xra(m, v); break;
    case 6: op_ora(m, v); break;
    default: op_sub(m, v, 0); break;
    }
}

static inline uint8_t op_inr(Spec* m, uint8_t v) {
    v++;
    C->fac = (v & 0x0F) == 0;
    szp(m, v);
    return v;
}

static inline uint8_t op_dcr(Spec* m, uint8_t v) {
    v--;
    C->fac = (v & 0x0F) != 0x0F;
    szp(m, v);
    return v;
}

static void op_daa(Spec* m) {
    uint8_t corr = 0;
    uint8_t cy = C->fcy;
    uint8_t lsb = C->a & 0x0F, msb = C->a >> 4;
    if(C->fac || lsb > 9) corr += 0x06;
    if(C->fcy || msb > 9 || (msb >= 9 && lsb > 9)) {
        corr += 0x60;
        cy = 1;
    }
    op_add(m, corr, 0);
    C->fcy = cy;
}

static inline void op_dad(Spec* m, uint16_t v) {
    uint32_t res = (uint32_t)HL + v;
    C->fcy = (res & 0x10000) != 0;
    set_hl(m, (uint16_t)res);
}

static inline bool cond(Spec* m, int cc) {
    switch(cc) {
    case 0: return !C->fz;
    case 1: return C->fz;
    case 2: return !C->fcy;
    case 3: return C->fcy;
    case 4: return !C->fp;
    case 5: return C->fp;
    case 6: return !C->fs;
    default: return C->fs;
    }
}

int i8080_step(Spec* m) {
    if(C->ei_pending && --C->ei_pending == 0) C->inte = 1;
    if(C->halted) return 4;
    uint8_t op = fetch(m);
    int cyc = CYCLES[op];

    if(op >= 0x40 && op < 0x80) {
        if(op == 0x76) {
            C->halted = 1;
            return cyc;
        }
        set_reg(m, (op >> 3) & 7, get_reg(m, op & 7));
        return cyc;
    }
    if(op >= 0x80 && op < 0xC0) {
        alu(m, (op >> 3) & 7, get_reg(m, op & 7));
        return cyc;
    }

    switch(op) {
    case 0x00: case 0x08: case 0x10: case 0x18:
    case 0x20: case 0x28: case 0x30: case 0x38:
        break;
    case 0x01: set_bc(m, fetch16(m)); break;
    case 0x11: set_de(m, fetch16(m)); break;
    case 0x21: set_hl(m, fetch16(m)); break;
    case 0x31: C->sp = fetch16(m); break;
    case 0x02: wr(m, BC, C->a); break;
    case 0x12: wr(m, DE, C->a); break;
    case 0x0A: C->a = rd(m, BC); break;
    case 0x1A: C->a = rd(m, DE); break;
    case 0x22: wr16(m, fetch16(m), HL); break;
    case 0x2A: set_hl(m, rd16(m, fetch16(m))); break;
    case 0x32: wr(m, fetch16(m), C->a); break;
    case 0x3A: C->a = rd(m, fetch16(m)); break;
    case 0x03: set_bc(m, (uint16_t)(BC + 1)); break;
    case 0x13: set_de(m, (uint16_t)(DE + 1)); break;
    case 0x23: set_hl(m, (uint16_t)(HL + 1)); break;
    case 0x33: C->sp++; break;
    case 0x0B: set_bc(m, (uint16_t)(BC - 1)); break;
    case 0x1B: set_de(m, (uint16_t)(DE - 1)); break;
    case 0x2B: set_hl(m, (uint16_t)(HL - 1)); break;
    case 0x3B: C->sp--; break;
    case 0x09: op_dad(m, BC); break;
    case 0x19: op_dad(m, DE); break;
    case 0x29: op_dad(m, HL); break;
    case 0x39: op_dad(m, C->sp); break;
    case 0x04: case 0x0C: case 0x14: case 0x1C:
    case 0x24: case 0x2C: case 0x34: case 0x3C: {
        int r = (op >> 3) & 7;
        set_reg(m, r, op_inr(m, get_reg(m, r)));
        break;
    }
    case 0x05: case 0x0D: case 0x15: case 0x1D:
    case 0x25: case 0x2D: case 0x35: case 0x3D: {
        int r = (op >> 3) & 7;
        set_reg(m, r, op_dcr(m, get_reg(m, r)));
        break;
    }
    case 0x06: case 0x0E: case 0x16: case 0x1E:
    case 0x26: case 0x2E: case 0x36: case 0x3E:
        set_reg(m, (op >> 3) & 7, fetch(m));
        break;
    case 0x07: C->fcy = (C->a & 0x80) != 0; C->a = (uint8_t)((C->a << 1) | C->fcy); break;
    case 0x0F: C->fcy = C->a & 1; C->a = (uint8_t)((C->a >> 1) | (C->fcy << 7)); break;
    case 0x17: { uint8_t cy = C->fcy; C->fcy = (C->a & 0x80) != 0; C->a = (uint8_t)((C->a << 1) | cy); break; }
    case 0x1F: { uint8_t cy = C->fcy; C->fcy = C->a & 1; C->a = (uint8_t)((C->a >> 1) | (cy << 7)); break; }
    case 0x27: op_daa(m); break;
    case 0x2F: C->a = (uint8_t)~C->a; break;
    case 0x37: C->fcy = 1; break;
    case 0x3F: C->fcy = !C->fcy; break;

    case 0xC3: case 0xCB: C->pc = fetch16(m); break;
    case 0xC2: case 0xCA: case 0xD2: case 0xDA:
    case 0xE2: case 0xEA: case 0xF2: case 0xFA: {
        uint16_t a = fetch16(m);
        if(cond(m, (op >> 3) & 7)) C->pc = a;
        break;
    }
    case 0xCD: case 0xDD: case 0xED: case 0xFD: {
        uint16_t a = fetch16(m);
        push(m, C->pc);
        C->pc = a;
        break;
    }
    case 0xC4: case 0xCC: case 0xD4: case 0xDC:
    case 0xE4: case 0xEC: case 0xF4: case 0xFC: {
        uint16_t a = fetch16(m);
        if(cond(m, (op >> 3) & 7)) {
            push(m, C->pc);
            C->pc = a;
            cyc += 6;
        }
        break;
    }
    case 0xC9: case 0xD9: C->pc = pop(m); break;
    case 0xC0: case 0xC8: case 0xD0: case 0xD8:
    case 0xE0: case 0xE8: case 0xF0: case 0xF8:
        if(cond(m, (op >> 3) & 7)) {
            C->pc = pop(m);
            cyc += 6;
        }
        break;
    case 0xC7: case 0xCF: case 0xD7: case 0xDF:
    case 0xE7: case 0xEF: case 0xF7: case 0xFF:
        push(m, C->pc);
        C->pc = op & 0x38;
        break;
    case 0xE9: C->pc = HL; break;

    case 0xC5: push(m, BC); break;
    case 0xD5: push(m, DE); break;
    case 0xE5: push(m, HL); break;
    case 0xF5: push(m, (uint16_t)((C->a << 8) | get_f(m))); break;
    case 0xC1: set_bc(m, pop(m)); break;
    case 0xD1: set_de(m, pop(m)); break;
    case 0xE1: set_hl(m, pop(m)); break;
    case 0xF1: { uint16_t v = pop(m); C->a = (uint8_t)(v >> 8); set_f(m, (uint8_t)v); break; }
    case 0xE3: { uint16_t v = rd16(m, C->sp); wr16(m, C->sp, HL); set_hl(m, v); break; }
    case 0xF9: C->sp = HL; break;
    case 0xEB: { uint16_t t = HL; set_hl(m, DE); set_de(m, t); break; }

    case 0xC6: op_add(m, fetch(m), 0); break;
    case 0xCE: op_add(m, fetch(m), C->fcy); break;
    case 0xD6: C->a = op_sub(m, fetch(m), 0); break;
    case 0xDE: C->a = op_sub(m, fetch(m), C->fcy); break;
    case 0xE6: op_ana(m, fetch(m)); break;
    case 0xEE: op_xra(m, fetch(m)); break;
    case 0xF6: op_ora(m, fetch(m)); break;
    case 0xFE: op_sub(m, fetch(m), 0); break;

    case 0xD3: (void)fetch(m); break;          /* OUT: no ports on the Specialist */
    case 0xDB: (void)fetch(m); C->a = 0xFF; break; /* IN */
    case 0xF3: C->inte = 0; C->ei_pending = 0; break;
    case 0xFB: C->ei_pending = 2; break;
    default: break;
    }
    return cyc;
}

#undef C

/* ------------------------------------------------------------------ machine */

void spec_init(Spec* m, uint8_t* mem64k) {
    init_tables();
    memset(m, 0, sizeof(*m));
    m->mem = mem64k;
    memset(m->mem, 0, 0x10000);
    memset(m->mem + 0xC000, 0xFF, 0x4000);
    spec_reset(m);
}

void spec_load_rom(Spec* m, const uint8_t* data, size_t len) {
    memset(m->mem + 0xC000, 0xFF, SPEC_ROM_SIZE);
    if(len > SPEC_ROM_SIZE) len = SPEC_ROM_SIZE;
    memcpy(m->mem + 0xC000, data, len);
    m->rom_loaded = true;
}

void spec_reset(Spec* m) {
    m->cpu.pc = 0xC000;
    m->cpu.halted = 0;
    m->cpu.inte = 0;
    m->cpu.ei_pending = 0;
    m->a_in = m->b_in = m->clo_in = m->chi_in = true;
    m->port_a = m->port_b = m->port_c = 0;
    apply_port_c(m);
}

void spec_hard_reset(Spec* m) {
    memset(m->mem, 0, 0xC000);
    memset(&m->cpu, 0, sizeof(m->cpu));
    spec_reset(m);
}

void spec_run(Spec* m, int32_t cycles) {
    while(cycles > 0) cycles -= i8080_step(m);
}

static void set_key(Spec* m, int code, bool down) {
    if(code == SPEC_KEY_SHIFT) {
        m->shift = down;
        return;
    }
    int col = code / SPEC_ROWS;
    uint8_t bit = (uint8_t)(1u << (code % SPEC_ROWS));
    if(down) m->key_cols[col] |= bit;
    else m->key_cols[col] &= (uint8_t)~bit;
}

void spec_key_down(Spec* m, int code) {
    if(code < 0 || code >= SPEC_KEY_COUNT) return;
    if(m->press_count[code]++ == 0) set_key(m, code, true);
}

void spec_key_up(Spec* m, int code) {
    if(code < 0 || code >= SPEC_KEY_COUNT) return;
    if(m->press_count[code] > 0 && --m->press_count[code] == 0) set_key(m, code, false);
}

void spec_release_all(Spec* m) {
    memset(m->press_count, 0, sizeof(m->press_count));
    memset(m->key_cols, 0, sizeof(m->key_cols));
    m->shift = false;
}

uint32_t spec_take_toggles(Spec* m) {
    uint32_t t = m->beeper_toggles;
    m->beeper_toggles = 0;
    return t;
}

bool spec_rks_header(const uint8_t* data, size_t n, size_t* data_off, uint16_t* beg, uint16_t* end) {
    size_t p = 0;
    if(n > 0 && data[0] == 0xE6) p++;
    if(n >= p + 3 && data[p] == 0xD9 && data[p + 1] == 0xD9 && data[p + 2] == 0xD9) {
        p += 3;
        while(p < n && data[p] != 0xE6) p++;
        p++;
    }
    if(p + 4 > n) return false;
    uint16_t b = (uint16_t)(data[p] | (data[p + 1] << 8));
    uint16_t e = (uint16_t)(data[p + 2] | (data[p + 3] << 8));
    if(b == 0xE6E6 || b == 0xD3D3 || e < b) return false;
    *data_off = p + 4;
    *beg = b;
    *end = e;
    return true;
}

void spec_prepare_run(Spec* m) {
    spec_reset(m);
    spec_run(m, 600000); /* let the monitor initialise */
}

void spec_poke(Spec* m, uint16_t addr, uint8_t v) {
    wr(m, addr, v);
}

void spec_start_at(Spec* m, uint16_t addr) {
    spec_release_all(m);
    m->cpu.halted = 0;
    m->cpu.pc = addr;
}

bool spec_load_rks(Spec* m, const uint8_t* data, size_t n, bool run, uint16_t* start, uint16_t* end) {
    size_t off;
    uint16_t beg, fin;
    if(!spec_rks_header(data, n, &off, &beg, &fin)) return false;
    size_t len = (size_t)(fin - beg) + 1;
    if(len > n - off) return false;
    if(run) spec_prepare_run(m);
    for(size_t i = 0; i < len; i++) wr(m, (uint16_t)(beg + i), data[off + i]);
    if(run) spec_start_at(m, beg);
    if(start) *start = beg;
    if(end) *end = fin;
    return true;
}

/* ------------------------------------------------------------------ rendering */

static inline int px(const uint8_t* vram, int x, int y) {
    return (vram[(x >> 3) * 256 + y] >> (7 - (x & 7))) & 1;
}

void spec_view_size(SpecView view, int* w, int* h) {
    switch(view) {
    case SPEC_VIEW_1X: *w = 128; *h = 64; break;
    case SPEC_VIEW_2X: *w = 256; *h = 128; break;
    default: *w = SPEC_W; *h = SPEC_H; break;
    }
}

/* Output is the Flipper/u8g2 native frame buffer layout: 8 pages of 128 bytes,
 * byte = 8 vertical pixels, bit 0 = top. Copying it into the canvas is a plain memcpy. */
static inline void put(uint8_t* fb, int x, int y) {
    fb[(y >> 3) * 128 + x] |= (uint8_t)(1u << (y & 7));
}

void spec_render(const Spec* m, uint8_t* fb, SpecView view, int vx, int vy) {
    const uint8_t* vram = m->mem + 0x9000;
    memset(fb, 0, 1024);
    if(view == SPEC_VIEW_1X) {
        int col0 = vx >> 3; /* vx is a multiple of 8 */
        for(int page = 0; page < 8; page++) {
            for(int bx = 0; bx < 16; bx++) {
                const uint8_t* src = vram + (col0 + bx) * 256 + vy + page * 8;
                uint8_t* dst = fb + page * 128 + bx * 8;
                /* transpose an 8x8 block: 8 horizontal source bytes -> 8 vertical output bytes */
                for(int k = 0; k < 8; k++) {
                    uint8_t b = src[k];
                    if(!b) continue;
                    uint8_t bit = (uint8_t)(1u << k);
                    for(int j = 0; j < 8; j++)
                        if(b & (0x80 >> j)) dst[j] |= bit;
                }
            }
        }
    } else if(view == SPEC_VIEW_2X) {
        /* 2x2 block -> 1 pixel, lit when at least 2 of 4 are lit: keeps 1-px lines, drops lone dots */
        for(int y = 0; y < 64; y++) {
            int sy = vy + y * 2;
            for(int x = 0; x < 128; x++) {
                int sx = vx + x * 2;
                if(px(vram, sx, sy) + px(vram, sx + 1, sy) + px(vram, sx, sy + 1) + px(vram, sx + 1, sy + 1) >= 2)
                    put(fb, x, y);
            }
        }
    } else {
        /* whole screen: 3x4 block -> 1 pixel, lit when at least 2 of 12 source pixels are lit */
        for(int y = 0; y < 64; y++) {
            for(int x = 0; x < 128; x++) {
                int sx = x * 3, sy = y * 4, cnt = 0;
                for(int dy = 0; dy < 4; dy++)
                    for(int dx = 0; dx < 3; dx++) cnt += px(vram, sx + dx, sy + dy);
                if(cnt >= 2) put(fb, x, y);
            }
        }
    }
}
