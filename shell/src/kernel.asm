; =====================================================================================
;  Kernel: 64x32 text with 6x8 cells over the 384x256 bitmap, keyboard, small helpers
; =====================================================================================

; clear the whole screen
CLS:    PUSH H
        LXI H,0
        SHLD V_FILL
        CALL B_CLS
        POP H
        RET

; B = column, C = row
GOTOXY: MOV A,B
        STA CURX
        MOV A,C
        STA CURY
        RET

INVON:  MVI A,3Fh
        STA INV
        RET
INVOFF: XRA A
        STA INV
        RET

; print character A at (CURX, CURY) and advance; keeps all registers
PUTCH:  PUSH PSW
        PUSH H
        PUSH D
        PUSH B
        MOV C,A
        ; screen address: D = 90h + byte column, E = row * 8; B = pixel shift 0/2/4/6
        LDA CURX
        ADD A
        MOV E,A
        MVI D,0
        LXI H,COLTAB
        DAD D
        MOV A,M
        ADI 90h
        MOV D,A
        INX H
        MOV B,M
        LDA CURY
        ADD A
        ADD A
        ADD A
        MOV E,A
        PUSH D
        PUSH B
        ; glyph rows: 6 bits each, bit 5 = left
        MOV L,C
        MVI H,0
        DAD H
        DAD H
        DAD H
        MOV A,C
        ORA A
        JM .hi
        LXI D,FONT
        JMP .g
.hi:    LXI D,GLYPHS-400h
.g:     DAD D
        LDA INV
        ORA A
        JZ .direct
        LXI D,GBUF              ; inverse: complemented copy
        MVI B,8
.cp:    MOV A,M
        XRI 3Fh
        STAX D
        INX H
        INX D
        DCR B
        JNZ .cp
        LXI H,GBUF
.direct:
        XCHG
        POP B
        POP H
        MOV A,B
        ORA A
        JZ .s0
        CPI 2
        JZ .s2
        CPI 4
        JZ .s4
        ; shift 6: 2 bits in this byte, 4 bits in the next
.s6:    LDAX D
        RRC
        RRC
        RRC
        RRC
        MOV B,A
        ANI 03h
        MOV C,A
        MOV A,M
        ANI 0FCh
        ORA C
        MOV M,A
        INR H
        MOV A,B
        ANI 0F0h
        MOV C,A
        MOV A,M
        ANI 0Fh
        ORA C
        MOV M,A
        DCR H
        INX D
        INR L
        MOV A,L
        ANI 7
        JNZ .s6
        JMP .done
.s4:    LDAX D
        RRC
        RRC
        MOV B,A
        ANI 0Fh
        MOV C,A
        MOV A,M
        ANI 0F0h
        ORA C
        MOV M,A
        INR H
        MOV A,B
        ANI 0C0h
        MOV C,A
        MOV A,M
        ANI 3Fh
        ORA C
        MOV M,A
        DCR H
        INX D
        INR L
        MOV A,L
        ANI 7
        JNZ .s4
        JMP .done
.s2:    LDAX D
        MOV C,A
        MOV A,M
        ANI 0C0h
        ORA C
        MOV M,A
        INX D
        INR L
        MOV A,L
        ANI 7
        JNZ .s2
        JMP .done
.s0:    LDAX D
        ADD A
        ADD A
        MOV C,A
        MOV A,M
        ANI 03h
        ORA C
        MOV M,A
        INX D
        INR L
        MOV A,L
        ANI 7
        JNZ .s0
.done:  LXI H,CURX
        INR M
        POP B
        POP D
        POP H
        POP PSW
        RET

; print string (HL) up to 00; 01 = inverse on, 02 = inverse off. HL ends after the 00.
; Aligned runs of 4 characters go through GRP4 (about twice as fast).
PUTS:   LDA CURX
        ANI 3
        JNZ .one
        LDA CURX
        CPI 61
        JNC .one
        CALL GRP4
        JNC PUTS
.one:   MOV A,M
        INX H
        ORA A
        RZ
        CPI 3
        JNC .ch
        DCR A
        JZ .on
        CALL INVOFF
        JMP PUTS
.on:    CALL INVON
        JMP PUTS
.ch:    CALL PUTCH
        JMP PUTS

; print the string that follows the CALL (00-terminated), return after it
PRINT:  XTHL
        CALL PUTS
        XTHL
        RET

; B = column, C = row, then the string after the CALL
PRINTAT:
        CALL GOTOXY
        JMP PRINT

; A = character, B = count (A kept, B = 0 at the end)
REPCH:  STA CH4
        STA CH4+1
        STA CH4+2
        STA CH4+3
        INR B
.l:     DCR B
        JZ .end
        MOV A,B
        CPI 4
        JC .one
        LDA CURX
        ANI 3
        JNZ .one
        LDA CURX
        CPI 61
        JNC .one
        PUSH H
        LXI H,CH4
        CALL GRP4
        POP H
        JC .one
        DCR B
        DCR B
        DCR B
        JMP .l
.one:   LDA CH4
        CALL PUTCH
        JMP .l
.end:   LDA CH4
        RET

; print A as two hex digits
PHEX8:  PUSH D
        CALL HEX2
        MOV A,D
        CALL PUTCH
        MOV A,E
        CALL PUTCH
        POP D
        RET

; print HL as four hex digits
PHEX16: MOV A,H
        CALL PHEX8
        MOV A,L
        JMP PHEX8

; whole text row C inverted and empty
BARROW: PUSH B
        MVI B,0
        CALL GOTOXY
        CALL INVON
        MVI A,' '
        MVI B,64
        CALL REPCH
        CALL INVOFF
        POP B
        RET

; frame: B,C = top-left, D,E = bottom-right (columns, rows), A = 0 single / 1 double
FRAME:  PUSH H
        LXI H,FR_SINGLE
        ORA A
        JZ .s
        LXI H,FR_DOUBLE
.s:     SHLD TMP
        ; top line
        CALL GOTOXY
        MOV A,M                 ; corner top-left
        CALL PUTCH
        INX H
        MOV A,D
        SUB B
        DCR A
        PUSH B
        MOV B,A
        MOV A,M                 ; horizontal
        CALL REPCH
        INX H
        MOV A,M                 ; corner top-right
        CALL PUTCH
        POP B
        ; sides
        PUSH B
.side:  INR C
        MOV A,C
        CMP E
        JNC .bot
        CALL GOTOXY
        LHLD TMP
        INX H
        INX H
        INX H
        MOV A,M                 ; vertical
        CALL PUTCH
        MOV A,D
        STA CURX
        MOV A,M
        CALL PUTCH
        JMP .side
.bot:   CALL GOTOXY
        LHLD TMP
        INX H
        INX H
        INX H
        INX H
        MOV A,M                 ; corner bottom-left
        CALL PUTCH
        DCX H
        DCX H
        DCX H
        MOV A,D
        SUB B
        DCR A
        PUSH B
        MOV B,A
        MOV A,M
        CALL REPCH
        POP B
        INX H
        INX H
        INX H
        INX H
        MOV A,M                 ; corner bottom-right
        CALL PUTCH
        POP B
        POP H
        RET

FR_SINGLE:  TEXT "┌─┐│└┘"
FR_DOUBLE:  TEXT "╔═╗║╚╝"

; ---------------------------------------------------------------- fast text (ROM-001)
; Four characters at a column divisible by 4 fill exactly 3 screen bytes per pixel row
; (4 x 6 = 24 bits), so they are written whole, without reading the screen first.
; HL -> 4 character codes. On success: CURX += 4, HL after them, carry clear. If one of
; them is a control code (< 3): nothing drawn, HL unchanged, carry set. Keeps BC, DE.
GRP4:   PUSH B
        PUSH D
        MOV B,M
        INX H
        MOV C,M
        INX H
        MOV D,M
        INX H
        MOV E,M
        MOV A,B
        CPI 3
        JC .no
        MOV A,C
        CPI 3
        JC .no
        MOV A,D
        CPI 3
        JC .no
        MOV A,E
        CPI 3
        JC .no
        INX H
        SHLD SPTR
        MOV A,E
        CALL GADR
        SHLD GP3
        MOV A,D
        CALL GADR
        SHLD GP2
        MOV A,C
        CALL GADR
        SHLD GP1
        MOV A,B
        CALL GADR
        XCHG                    ; DE = glyph 0
        LDA CURX                ; screen: column CURX / 4 * 3, row CURY * 8
        RRC
        RRC
        MOV C,A
        ADD A
        ADD C
        ADI 90h
        MOV H,A
        LDA CURY
        ADD A
        ADD A
        ADD A
        MOV L,A
        PUSH H                  ; for the inverse pass
        PUSH H
        LHLD GP1
        MOV B,H
        MOV C,L
        POP H
        ; byte 0 = glyph0 << 2 | glyph1 >> 4
        LDAX B
        RRC
        RRC
        RRC
        RRC
        ANI 03h
        MOV M,A
        LDAX D
        ADD A
        ADD A
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX B
        RRC
        RRC
        RRC
        RRC
        ANI 03h
        MOV M,A
        LDAX D
        ADD A
        ADD A
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX B
        RRC
        RRC
        RRC
        RRC
        ANI 03h
        MOV M,A
        LDAX D
        ADD A
        ADD A
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX B
        RRC
        RRC
        RRC
        RRC
        ANI 03h
        MOV M,A
        LDAX D
        ADD A
        ADD A
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX B
        RRC
        RRC
        RRC
        RRC
        ANI 03h
        MOV M,A
        LDAX D
        ADD A
        ADD A
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX B
        RRC
        RRC
        RRC
        RRC
        ANI 03h
        MOV M,A
        LDAX D
        ADD A
        ADD A
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX B
        RRC
        RRC
        RRC
        RRC
        ANI 03h
        MOV M,A
        LDAX D
        ADD A
        ADD A
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX B
        RRC
        RRC
        RRC
        RRC
        ANI 03h
        MOV M,A
        LDAX D
        ADD A
        ADD A
        ORA M
        MOV M,A
        INR L
        MOV A,C                 ; DE = glyph 1 (glyphs never cross a page: 8-aligned)
        SUI 7
        MOV E,A
        MOV D,B
        MOV A,L
        SUI 8
        MOV L,A
        INR H
        PUSH H
        LHLD GP2
        MOV B,H
        MOV C,L
        POP H
        ; byte 1 = glyph1 << 4 | glyph2 >> 2
        LDAX D
        RRC
        RRC
        RRC
        RRC
        ANI 0F0h
        MOV M,A
        LDAX B
        RRC
        RRC
        ANI 0Fh
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        RRC
        RRC
        ANI 0F0h
        MOV M,A
        LDAX B
        RRC
        RRC
        ANI 0Fh
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        RRC
        RRC
        ANI 0F0h
        MOV M,A
        LDAX B
        RRC
        RRC
        ANI 0Fh
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        RRC
        RRC
        ANI 0F0h
        MOV M,A
        LDAX B
        RRC
        RRC
        ANI 0Fh
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        RRC
        RRC
        ANI 0F0h
        MOV M,A
        LDAX B
        RRC
        RRC
        ANI 0Fh
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        RRC
        RRC
        ANI 0F0h
        MOV M,A
        LDAX B
        RRC
        RRC
        ANI 0Fh
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        RRC
        RRC
        ANI 0F0h
        MOV M,A
        LDAX B
        RRC
        RRC
        ANI 0Fh
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        RRC
        RRC
        ANI 0F0h
        MOV M,A
        LDAX B
        RRC
        RRC
        ANI 0Fh
        ORA M
        MOV M,A
        INR L
        MOV A,C
        SUI 7
        MOV E,A
        MOV D,B
        MOV A,L
        SUI 8
        MOV L,A
        INR H
        PUSH H
        LHLD GP3
        MOV B,H
        MOV C,L
        POP H
        ; byte 2 = glyph2 << 6 | glyph3
        LDAX D
        RRC
        RRC
        ANI 0C0h
        MOV M,A
        LDAX B
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        ANI 0C0h
        MOV M,A
        LDAX B
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        ANI 0C0h
        MOV M,A
        LDAX B
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        ANI 0C0h
        MOV M,A
        LDAX B
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        ANI 0C0h
        MOV M,A
        LDAX B
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        ANI 0C0h
        MOV M,A
        LDAX B
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        ANI 0C0h
        MOV M,A
        LDAX B
        ORA M
        MOV M,A
        INR E
        INR C
        INR L
        LDAX D
        RRC
        RRC
        ANI 0C0h
        MOV M,A
        LDAX B
        ORA M
        MOV M,A
        INR L
        POP H
        LDA INV
        ORA A
        CNZ INV3
        LHLD SPTR
        LDA CURX
        ADI 4                   ; clears carry
        STA CURX
        POP D
        POP B
        RET
.no:    DCX H
        DCX H
        DCX H
        POP D
        POP B
        STC
        RET

; invert the 3 x 8 bytes from HL (one 4-character group)
INV3:   MVI B,3
.c:
        MOV A,M
        CMA
        MOV M,A
        INR L
        MOV A,M
        CMA
        MOV M,A
        INR L
        MOV A,M
        CMA
        MOV M,A
        INR L
        MOV A,M
        CMA
        MOV M,A
        INR L
        MOV A,M
        CMA
        MOV M,A
        INR L
        MOV A,M
        CMA
        MOV M,A
        INR L
        MOV A,M
        CMA
        MOV M,A
        INR L
        MOV A,M
        CMA
        MOV M,A
        INR L
        MOV A,L
        SUI 8
        MOV L,A
        INR H
        DCR B
        JNZ .c
        RET

; HL = glyph address of character A (glyphs are 8-aligned; 4 pages per half of the set)
GADR:   RLC
        RLC
        RLC
        MOV H,A
        ANI 07h
        ADI LOW(PAGETAB)
        MOV L,A
        MOV A,H
        MVI H,HIGH(PAGETAB)
        MOV H,M
        ANI 0F8h
        MOV L,A
        RET
PAGETAB: DB HIGH(FONT), HIGH(FONT)+1, HIGH(FONT)+2, HIGH(FONT)+3
        DB HIGH(GLYPHS), HIGH(GLYPHS)+1, HIGH(GLYPHS)+2, HIGH(GLYPHS)+3
        ASSERT LOW(PAGETAB) <= 0F8h, "PAGETAB crosses a page"

; ---------------------------------------------------------------- scrolling (ROM-001)
; Text rows move by copying screen memory; the stack serves as a fast second pointer (the
; Specialist has no interrupts). B = top row, C = bottom row, B < C. Changes A, BC, DE, HL.
; SCROLLUP: rows B+1..C move up one row, row C is cleared.
SCROLLUP:
        LXI H,0
        DAD SP
        SHLD SSAVE
        MOV A,E
        ADI 90h
        STA SCREND
        MOV A,C
        SUB B
        MOV C,A                 ; text rows to move
        MOV A,B
        ADD A
        ADD A
        ADD A
        MOV B,A                 ; first destination byte in a column
        MVI H,90h
.col:   MOV A,B
        ADI 8
        MOV L,A
        SPHL                    ; SP = source
        MOV L,B                 ; HL = destination
        MOV A,C
.row:
        POP D
        MOV M,E
        INR L
        MOV M,D
        INR L
        POP D
        MOV M,E
        INR L
        MOV M,D
        INR L
        POP D
        MOV M,E
        INR L
        MOV M,D
        INR L
        POP D
        MOV M,E
        INR L
        MOV M,D
        INR L
        DCR A
        JNZ .row
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        INR H
        LDA SCREND
        CMP H
        JNZ .col
        LHLD SSAVE
        SPHL
        RET

; SCROLLDN: rows B..C-1 move down one row, row B is cleared.
SCROLLDN:
        LXI H,0
        DAD SP
        SHLD SSAVE
        MOV A,E
        ADI 90h
        STA SCREND
        MOV A,C
        SUB B
        MOV E,A
        MOV A,C
        ADD A
        ADD A
        ADD A
        DCR A
        MOV B,A                 ; last source byte in a column
        MOV C,E                 ; text rows to move
        MVI H,90h
.col:   MOV L,B
        LXI D,9
        DAD D
        SPHL                    ; SP = one past the last destination byte
        LXI D,-9
        DAD D                   ; HL = last source byte
        MOV A,C
.row:
        MOV D,M
        DCR L
        MOV E,M
        DCR L
        PUSH D
        MOV D,M
        DCR L
        MOV E,M
        DCR L
        PUSH D
        MOV D,M
        DCR L
        MOV E,M
        DCR L
        PUSH D
        MOV D,M
        DCR L
        MOV E,M
        DCR L
        PUSH D
        DCR A
        JNZ .row
        LXI D,0
        PUSH D
        PUSH D
        PUSH D
        PUSH D
        INR H
        LDA SCREND
        CMP H
        JNZ .col
        LHLD SSAVE
        SPHL
        RET

; ---------------------------------------------------------------- building lines (LBUF)
; Programs build a line in LBUF and print it with PUTS in one go from a column divisible
; by 4: that takes the fast path (GRP4). HL = position in the buffer.

; A as two hex digits
BHEX8:  PUSH PSW
        RRC
        RRC
        RRC
        RRC
        CALL HEXDIG
        MOV M,A
        INX H
        POP PSW
        CALL HEXDIG
        MOV M,A
        INX H
        RET

; DE as four hex digits
BHEX16: MOV A,D
        CALL BHEX8
        MOV A,E
        JMP BHEX8

; A as eight binary digits; changes B, C
BBIN8:  MVI C,8
        MOV B,A
.l:     MOV A,B
        RLC
        MOV B,A
        MVI A,'0'
        ACI 0
        MOV M,A
        INX H
        DCR C
        JNZ .l
        RET

; A in decimal without leading zeros; changes B, C
BDEC:   PUSH D
        MVI C,0
        MVI B,100
        CALL .d
        MVI B,10
        CALL .d
        ADI '0'
        MOV M,A
        INX H
        POP D
        RET
.d:     MVI D,'0'
.s:     CMP B
        JC .e
        SUB B
        INR D
        JMP .s
.e:     MOV E,A
        MOV A,D
        CPI '0'
        JNZ .w
        MOV A,C
        ORA A
        JZ .k
.w:     MOV M,D
        INX H
        MVI C,1
.k:     MOV A,E
        RET

; copy the 00-terminated string (DE) without the 00; DE ends after it
BSTR:   LDAX D
        INX D
        ORA A
        RZ
        MOV M,A
        INX H
        JMP BSTR

; spaces up to C characters in LBUF
BPADTO: MOV A,L
        CMP C
        RNC
        MVI M,' '
        INX H
        JMP BPADTO

; finish the line at HL and print LBUF at column B, row C
BPRINT: MVI M,0
        CALL GOTOXY
        LXI H,LBUF
        JMP PUTS

; ---------------------------------------------------------------- hex input
; A = key code -> A = 0..15, carry clear; carry set if not a hex digit. Letters A-F in both
; layouts and cases (Latin a-f, A-F; Russian а б ц д е ф on the same keys). Keeps BC, DE, HL.
HEXVAL: CPI '0'
        JC .no
        CPI '9'+1
        JNC .let
        SUI '0'
        RET
.let:   PUSH B
        MOV B,A
        ANI 0Fh
        JZ .nob
        CPI 7
        JNC .nob
        MOV C,A
        MOV A,B
        ANI 0F0h
        CPI 40h
        JZ .ok
        CPI 60h
        JZ .ok
        CPI 80h
        JZ .ok
        CPI 0A0h
        JZ .ok
.nob:   POP B
.no:    STC
        RET
.ok:    MOV A,C
        ADI 9
        POP B
        ORA A
        RET

; Read up to B hex digits at the text cursor into INBUF (values 0..15). ВК ends (carry clear),
; АР2 cancels (carry set); ← deletes. C = number of digits. Changes A, C, D.
HEXIN:  MVI C,0
.k:     MVI A,'_'
        CALL PUTCH
        LDA CURX
        DCR A
        STA CURX
        CALL GETKEY
        CPI 1Bh
        JZ .esc
        CPI 0Dh
        JZ .ok
        CPI 08h
        JZ .bs
        CALL HEXVAL
        JC .k
        MOV D,A
        MOV A,C
        CMP B
        JNC .k
        PUSH H
        ADI LOW(INBUF)
        MOV L,A
        MVI H,HIGH(INBUF)
        MOV M,D
        POP H
        INR C
        MOV A,D
        CALL HEXDIG
        CALL PUTCH
        JMP .k
.bs:    MOV A,C
        ORA A
        JZ .k
        DCR C
        MVI A,' '
        CALL PUTCH
        LDA CURX
        SUI 2
        STA CURX
        JMP .k
.ok:    MVI A,' '
        CALL PUTCH
        ORA A
        RET
.esc:   MVI A,' '
        CALL PUTCH
        STC
        RET

; HL = HL - DE, carry if it went below zero
SUB16:  MOV A,L
        SUB E
        MOV L,A
        MOV A,H
        SBB D
        MOV H,A
        RET

; HL = the last (up to 4) digits of INBUF; C = digit count. Changes A, C, DE.
DIG2HL: LXI H,0
        LXI D,INBUF
.l:     MOV A,C
        ORA A
        RZ
        DAD H
        DAD H
        DAD H
        DAD H
        LDAX D
        ORA L
        MOV L,A
        INX D
        DCR C
        JMP .l

; ---------------------------------------------------------------- keyboard
; Scan the 12 x 6 matrix. Returns A = column*6 + row of the first pressed key, FF if none.
; Shift (НР) state in SHIFTK (1 = held). Keeps BC, DE, HL.
KSCAN:  PUSH B
        PUSH D
        PUSH H
        MVI A,82h               ; A and C out, B in
        STA PPI_CTL
        MVI A,0Fh
        STA PPI_C
        MVI B,0                 ; column
        MVI D,0FEh              ; column select pattern (low = selected)
.c1:    MOV A,D
        STA PPI_A
        CALL .rd
        JNZ .hit
        MOV A,D
        RLC
        MOV D,A
        INR B
        MOV A,B
        CPI 8
        JNZ .c1
        MVI A,0FFh
        STA PPI_A
        MVI D,0FEh
.c2:    MOV A,D
        ANI 0Fh
        STA PPI_C
        CALL .rd
        JNZ .hit
        MOV A,D
        RLC
        MOV D,A
        INR B
        MOV A,B
        CPI 12
        JNZ .c2
        MVI A,0FFh
        JMP .out
.hit:   ; E = rows (bit 2 = row 0); A = B * 6 + row
        MOV A,B
        ADD A
        ADD B
        ADD A
        MOV C,A
        MOV A,E
        RRC
        RRC
.r:     RRC
        JC .rf
        INR C
        JMP .r
.rf:    MOV A,C
.out:   PUSH PSW
        MVI A,0FFh
        STA PPI_A
        MVI A,0Fh
        STA PPI_C
        LDA PPI_B
        CMA
        RRC
        RRC
        ANI 1
        STA SHIFTK
        POP PSW
        POP H
        POP D
        POP B
        RET
.rd:    LDA PPI_B
        CMA
        ANI 0FCh
        MOV E,A
        RET

; Wait for a key and return its shell code in A (with autorepeat). Р/Л switches letters.
GETKEY: PUSH H
        PUSH B
.wait:  CALL KSCAN
        CPI 0FFh
        JNZ .key
        STA LASTK
        JMP .wait
.key:   MOV B,A
        LDA LASTK
        CMP B
        JZ .held
        ; new key: confirm after ~5 ms (contact bounce)
        LXI H,300
        CALL DELAY
        CALL KSCAN
        CMP B
        JNZ .wait
        STA LASTK
        LXI H,1100              ; first repeat after ~0.45 s
        SHLD REPCNT
        JMP .give
.held:  LHLD REPCNT
        DCX H
        SHLD REPCNT
        MOV A,H
        ORA L
        JNZ .wait
        LXI H,120               ; then about 15 per second
        SHLD REPCNT
.give:  ; translate
        MOV A,B
        MOV C,A
        MVI B,0
        LXI H,KEYTAB_LAT
        LDA RUS
        ORA A
        JZ .t1
        LXI H,KEYTAB_RUS
.t1:    LDA SHIFTK
        ORA A
        JZ .t2
        PUSH B
        LXI B,72
        DAD B
        POP B
.t2:    DAD B
        MOV A,M
        CPI 0FEh                ; Р/Л
        JNZ .done
        LDA RUS
        XRI 1
        STA RUS
        CALL SHOW_LANG
        JMP .wait
.done:  ORA A
        JZ .wait                ; ПВ and unused keys
        POP B
        POP H
        RET

; busy wait: HL * ~16 us
DELAY:  DCX H
        MOV A,H
        ORA L
        JNZ DELAY
        RET
