#!/usr/bin/env python3
"""
Small two-pass Intel 8080 assembler for the Specialist shell ROM.

  asm80.py main.asm -o out.bin --base C000 --size 3000 [-l listing] [-s symbols]

Syntax: Intel mnemonics (MOV, MVI, LXI, ...). Labels end with ':'; labels starting with '.'
are local to the last global label. Directives: ORG, EQU (or =), DB, DW, DS, TEXT, INCLUDE,
ALIGN, ASSERT, INCBIN. Expressions: + - * / % & | ^ << >> ~, ( ), $, 'c', 0x1F, 1Fh, 0b101, %101, 101b,
LOW(x), HIGH(x).
TEXT "string" encodes the shell character set (Russian, lowercase, box drawing) and adds no
terminator; DB "string" accepts only plain ASCII.
"""
import argparse
import os
import re
import sys

REG = {'B': 0, 'C': 1, 'D': 2, 'E': 3, 'H': 4, 'L': 5, 'M': 6, 'A': 7}
RP = {'B': 0, 'D': 1, 'H': 2, 'SP': 3}
RP_PUSH = {'B': 0, 'D': 1, 'H': 2, 'PSW': 3}
CC = {'NZ': 0, 'Z': 1, 'NC': 2, 'C': 3, 'PO': 4, 'PE': 5, 'P': 6, 'M': 7}

IMPLIED = {
    'NOP': 0x00, 'RLC': 0x07, 'RRC': 0x0F, 'RAL': 0x17, 'RAR': 0x1F, 'DAA': 0x27, 'CMA': 0x2F,
    'STC': 0x37, 'CMC': 0x3F, 'HLT': 0x76, 'RET': 0xC9, 'XTHL': 0xE3, 'XCHG': 0xEB, 'PCHL': 0xE9,
    'SPHL': 0xF9, 'DI': 0xF3, 'EI': 0xFB,
}
ALU_R = {'ADD': 0x80, 'ADC': 0x88, 'SUB': 0x90, 'SBB': 0x98, 'ANA': 0xA0, 'XRA': 0xA8, 'ORA': 0xB0, 'CMP': 0xB8}
ALU_I = {'ADI': 0xC6, 'ACI': 0xCE, 'SUI': 0xD6, 'SBI': 0xDE, 'ANI': 0xE6, 'XRI': 0xEE, 'ORI': 0xF6, 'CPI': 0xFE}
ADDR16 = {'JMP': 0xC3, 'CALL': 0xCD, 'SHLD': 0x22, 'LHLD': 0x2A, 'STA': 0x32, 'LDA': 0x3A}

# ---------------------------------------------------------------- character set
# 00-7F as in the Specialist monitor (KOI-7: 40-5F Latin capitals, 60-7F Russian capitals)
KOI7 = "ЮАБЦДЕФГХИЙКЛМНОПЯРСТУЖВЬЫЗШЭЩЧ"
RU_LOWER = "юабцдефгхийклмнопярстужвьызшэщч"
BOX = "─│┌┐└┘├┤┬┴┼═║╔╗╚╝╟╢█▒░▀▄▌▐·►◄▲▼✓"   # codes E0..FF
CHARMAP = {}
for i, ch in enumerate(KOI7):
    CHARMAP[ch] = 0x60 + i
for i, ch in enumerate(RU_LOWER):
    CHARMAP[ch] = 0x80 + i          # 80-9F Russian lowercase, same order
for i in range(26):
    CHARMAP[chr(ord('a') + i)] = 0xA1 + i   # A1-BA Latin lowercase
for i, ch in enumerate(BOX):
    CHARMAP[ch] = 0xE0 + i
CHARMAP['ъ'] = 0xBB             # not in KOI-7: extra glyphs
CHARMAP['Ъ'] = 0xBC
CHARMAP['ё'] = CHARMAP['е']
CHARMAP['Ё'] = CHARMAP['Е']
CHARMAP['—'] = ord('-')
CHARMAP['–'] = ord('-')
CHARMAP['«'] = ord('"')
CHARMAP['»'] = ord('"')
CHARMAP['→'] = CHARMAP['►']
CHARMAP['←'] = CHARMAP['◄']


def encode(s):
    out = []
    for ch in s:
        if ch in CHARMAP:
            out.append(CHARMAP[ch])
        elif ' ' <= ch <= '_':
            out.append(ord(ch))
        else:
            raise ValueError(f"character {ch!r} not in the shell charset")
    return out


class AsmError(Exception):
    pass


class Assembler:
    def __init__(self):
        self.sym = {}
        self.pass_no = 0
        self.out = {}
        self.listing = []
        self.errors = []

    # ------------------------------------------------------------ expressions
    def value(self, expr, line):
        e = expr.strip()
        if not e:
            raise AsmError("empty expression")
        toks = re.findall(r"'(?:[^'\\]|\\.)'|0x[0-9A-Fa-f]+|0b[01]+|%[01]+|[01]+[Bb]\b|[0-9][0-9A-Fa-f]*[Hh]\b|\d+|"
                          r"[A-Za-z_.@?][\w.@?]*|<<|>>|==|!=|<=|>=|[<>]|[-+*/%&|^~()$]", e)
        joined = ''.join(toks)
        if joined.replace(' ', '') != e.replace(' ', ''):
            raise AsmError(f"bad expression: {expr}")
        py = []
        for t in toks:
            if t.startswith("'"):
                body = t[1:-1]
                if body.startswith('\\'):
                    body = {'n': '\n', 'r': '\r', 't': '\t', '0': '\0', "'": "'", '\\': '\\'}[body[1]]
                py.append(str(encode(body)[0] if body not in '\n\r\t\0' else ord(body)))
            elif re.fullmatch(r'0x[0-9A-Fa-f]+', t):
                py.append(str(int(t, 16)))
            elif re.fullmatch(r'0b[01]+', t):
                py.append(str(int(t[2:], 2)))
            elif re.fullmatch(r'%[01]+', t):
                py.append(str(int(t[1:], 2)))
            elif re.fullmatch(r'[01]+[Bb]', t):
                py.append(str(int(t[:-1], 2)))
            elif re.fullmatch(r'[0-9][0-9A-Fa-f]*[Hh]', t):
                py.append(str(int(t[:-1], 16)))
            elif re.fullmatch(r'\d+', t):
                py.append(t)
            elif t == '$':
                py.append(str(self.pc))
            elif t in ('LOW', 'HIGH', 'low', 'high'):
                py.append('_' + t.lower())
            elif re.fullmatch(r'[A-Za-z_.@?][\w.@?]*', t):
                name = self.qualify(t)
                if name in self.sym:
                    py.append(str(self.sym[name]))
                elif self.pass_no == 1:
                    py.append('0')
                else:
                    raise AsmError(f"undefined symbol {t}")
            elif t == '/':
                py.append('//')
            elif t == '~':
                py.append('~')
            else:
                py.append(t)
        try:
            return int(eval(' '.join(py), {'__builtins__': {}}, {'_low': lambda v: v & 0xFF, '_high': lambda v: (v >> 8) & 0xFF}))
        except Exception as ex:
            raise AsmError(f"cannot evaluate {expr}: {ex}")

    def qualify(self, name):
        if name.startswith('.'):
            return self.scope + name
        return name

    # ------------------------------------------------------------ output
    def emit(self, *bs):
        for b in bs:
            if self.pass_no == 2:
                if self.pc in self.out:
                    raise AsmError(f"overlapping code at {self.pc:04X}")
                self.out[self.pc] = b & 0xFF
            self.pc = (self.pc + 1) & 0xFFFF

    def b8(self, expr):
        v = self.value(expr, None)
        if self.pass_no == 2 and not -128 <= v <= 255:
            raise AsmError(f"byte out of range: {expr} = {v}")
        return v & 0xFF

    def w16(self, expr):
        v = self.value(expr, None)
        if self.pass_no == 2 and not -32768 <= v <= 0xFFFF:
            raise AsmError(f"word out of range: {expr}")
        return v & 0xFFFF

    # ------------------------------------------------------------ parsing
    @staticmethod
    def split_args(s):
        args, cur, q = [], '', None
        for ch in s:
            if q:
                cur += ch
                if ch == q:
                    q = None
            elif ch in '"\'':
                q = ch
                cur += ch
            elif ch == ',':
                args.append(cur.strip())
                cur = ''
            else:
                cur += ch
        if cur.strip():
            args.append(cur.strip())
        return args

    @staticmethod
    def strip_comment(line):
        out, q = '', None
        for ch in line:
            if q:
                out += ch
                if ch == q:
                    q = None
            elif ch in '"\'':
                q = ch
                out += ch
            elif ch == ';':
                break
            else:
                out += ch
        return out.rstrip()

    def assemble_file(self, path):
        with open(path, encoding='utf-8') as f:
            lines = f.read().split('\n')
        base = os.path.dirname(path)
        for no, raw in enumerate(lines, 1):
            try:
                self.line(raw, base)
            except AsmError as e:
                self.errors.append(f"{path}:{no}: {e}\n    {raw.strip()}")
            except ValueError as e:
                self.errors.append(f"{path}:{no}: {e}\n    {raw.strip()}")

    def line(self, raw, base):
        start = self.pc
        line = self.strip_comment(raw)
        if not line.strip():
            return
        m = re.match(r'^\s*([A-Za-z_.@?][\w.@?]*)\s*:(.*)$', line)
        label = None
        if m:
            label, line = m.group(1), m.group(2)
        m2 = re.match(r'^\s*([A-Za-z_.@?][\w.@?]*)\s+(EQU|equ|=)\s+(.*)$', line) or \
            re.match(r'^\s*([A-Za-z_.@?][\w.@?]*)\s*(=)\s*(.*)$', line)
        if m2 and not label:
            name = self.qualify(m2.group(1))
            v = self.value(m2.group(3), raw)
            if self.pass_no == 2 and name in self.sym and self.sym[name] != v:
                raise AsmError(f"{name} changed between passes")
            self.sym[name] = v
            return
        if label:
            if not label.startswith('.'):
                self.scope = label
            name = self.qualify(label)
            if self.pass_no == 1 and name in self.sym:
                raise AsmError(f"duplicate label {label}")
            self.sym[name] = self.pc
        line = line.strip()
        if not line:
            return
        parts = line.split(None, 1)
        op = parts[0].upper()
        arg = parts[1] if len(parts) > 1 else ''
        args = self.split_args(arg)
        self.instr(op, args, arg, base)
        if self.pass_no == 2:
            n = (self.pc - start) & 0xFFFF
            bytes_ = [self.out.get((start + i) & 0xFFFF, 0) for i in range(min(n, 8))]
            self.listing.append(f"{start:04X}  {' '.join(f'{b:02X}' for b in bytes_):<24} {raw}")

    def instr(self, op, a, rawarg, base):
        if op == 'ORG':
            self.pc = self.w16(a[0])
        elif op == 'INCBIN':
            # INCBIN "file"[, offset[, length]]
            fn = os.path.join(base, a[0].strip('"'))
            data = open(fn, 'rb').read()
            off = self.value(a[1], None) if len(a) > 1 else 0
            n = self.value(a[2], None) if len(a) > 2 else len(data) - off
            for b in data[off:off + n]:
                self.emit(b)
        elif op == 'INCLUDE':
            self.assemble_file(os.path.join(base, rawarg.strip().strip('"')))
        elif op in ('DB', 'DEFB', 'BYTE'):
            for x in a:
                if x.startswith('"'):
                    for ch in x[1:-1]:
                        if not ' ' <= ch <= '~':
                            raise AsmError("DB strings are ASCII only, use TEXT")
                        self.emit(ord(ch))
                else:
                    self.emit(self.b8(x))
        elif op == 'TEXT':
            for x in a:
                if x.startswith('"'):
                    for c in encode(x[1:-1]):
                        self.emit(c)
                else:
                    self.emit(self.b8(x))
        elif op in ('DW', 'DEFW', 'WORD'):
            for x in a:
                v = self.w16(x)
                self.emit(v & 0xFF, v >> 8)
        elif op in ('DS', 'DEFS'):
            n = self.value(a[0], None)
            fill = self.b8(a[1]) if len(a) > 1 else 0
            if self.pass_no == 2:
                for _ in range(n):
                    self.emit(fill)
            else:
                self.pc = (self.pc + n) & 0xFFFF
        elif op == 'ALIGN':
            n = self.value(a[0], None)
            while self.pc % n:
                self.emit(0xFF)
        elif op == 'ASSERT':
            if self.pass_no == 2 and not self.value(a[0], None):
                raise AsmError(f"assertion failed: {a[0]}" + (f" ({a[1]})" if len(a) > 1 else ''))
        elif op in IMPLIED:
            self.emit(IMPLIED[op])
        elif op == 'MOV':
            d, s = REG[a[0].upper()], REG[a[1].upper()]
            if d == 6 and s == 6:
                raise AsmError("MOV M,M")
            self.emit(0x40 | d << 3 | s)
        elif op == 'MVI':
            self.emit(0x06 | REG[a[0].upper()] << 3, self.b8(a[1]))
        elif op == 'LXI':
            v = self.w16(a[1])
            self.emit(0x01 | RP[a[0].upper()] << 4, v & 0xFF, v >> 8)
        elif op in ('INR', 'DCR'):
            self.emit((0x04 if op == 'INR' else 0x05) | REG[a[0].upper()] << 3)
        elif op in ('INX', 'DCX', 'DAD'):
            self.emit({'INX': 0x03, 'DCX': 0x0B, 'DAD': 0x09}[op] | RP[a[0].upper()] << 4)
        elif op in ('PUSH', 'POP'):
            self.emit((0xC5 if op == 'PUSH' else 0xC1) | RP_PUSH[a[0].upper()] << 4)
        elif op in ('STAX', 'LDAX'):
            r = a[0].upper()
            if r not in ('B', 'D'):
                raise AsmError(f"{op} {r}")
            self.emit((0x02 if op == 'STAX' else 0x0A) | RP[r] << 4)
        elif op in ALU_R:
            self.emit(ALU_R[op] | REG[a[0].upper()])
        elif op in ALU_I:
            self.emit(ALU_I[op], self.b8(a[0]))
        elif op in ADDR16:
            v = self.w16(a[0])
            self.emit(ADDR16[op], v & 0xFF, v >> 8)
        elif op[0] in 'JCR' and op[1:] in CC and len(op) <= 3:
            c = CC[op[1:]]
            if op[0] == 'R':
                self.emit(0xC0 | c << 3)
            else:
                v = self.w16(a[0])
                self.emit((0xC2 if op[0] == 'J' else 0xC4) | c << 3, v & 0xFF, v >> 8)
        elif op == 'RST':
            self.emit(0xC7 | self.value(a[0], None) << 3)
        elif op in ('IN', 'OUT'):
            self.emit(0xDB if op == 'IN' else 0xD3, self.b8(a[0]))
        else:
            raise AsmError(f"unknown instruction {op}")

    def run(self, path):
        for p in (1, 2):
            self.pass_no = p
            self.pc = 0
            self.scope = ''
            self.listing = []
            self.assemble_file(path)
            if self.errors:
                break


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('src')
    ap.add_argument('-o', '--out', required=True)
    ap.add_argument('--base', default='C000')
    ap.add_argument('--size', default='3000')
    ap.add_argument('--fill', default='FF')
    ap.add_argument('-l', '--listing')
    ap.add_argument('-s', '--symbols')
    o = ap.parse_args()
    a = Assembler()
    a.run(o.src)
    if a.errors:
        for e in a.errors[:30]:
            print(e, file=sys.stderr)
        sys.exit(1)
    base, size = int(o.base, 16), int(o.size, 16)
    img = bytearray([int(o.fill, 16)] * size)
    for addr, b in a.out.items():
        if not base <= addr < base + size:
            print(f"byte outside the image at {addr:04X}", file=sys.stderr)
            sys.exit(1)
        img[addr - base] = b
    with open(o.out, 'wb') as f:
        f.write(img)
    if o.listing:
        with open(o.listing, 'w', encoding='utf-8') as f:
            f.write('\n'.join(a.listing) + '\n')
    if o.symbols:
        with open(o.symbols, 'w') as f:
            for k, v in sorted(a.sym.items(), key=lambda kv: kv[1]):
                f.write(f"{v:04X} {k}\n")
    used = len(a.out)
    print(f"{o.out}: {used} bytes used of {size} ({used * 100 // size}%)")


if __name__ == '__main__':
    main()
