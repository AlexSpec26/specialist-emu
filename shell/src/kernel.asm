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
PUTS:   MOV A,M
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

; A = character, B = count
REPCH:  INR B
.l:     DCR B
        RZ
        CALL PUTCH
        JMP .l

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
