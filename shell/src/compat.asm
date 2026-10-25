; =====================================================================================
;  Monitor-2 compatible services. They sit at the same addresses as in Monitor-2
;  (CE1D, CE5C, CE9D, CEC8), because a few programs call them there directly.
; =====================================================================================

; C81E: cursor position (H = X, L = Y in pixels)
M_GETCUR:
        LHLD V_CURSOR
        RET

; C82A: monitor checksum of HL..DE -> BC
M_CHECKSUM:
        LXI B,0
.lp:    MOV A,M
        ADD C
        MOV C,A
        PUSH PSW
        CALL B_CMP_HL_DE
        JZ .last
        POP PSW
        MOV A,B
        ADC M
        MOV B,A
        INX H
        JMP .lp
.last:  POP PSW
        RET

M_SETTOP:
        SHLD V_MEMTOP
        RET
M_GETTOP:
        LHLD V_MEMTOP
        RET



        ; a program fits here; Monitor-2's own bytes must stay from CD21 on, because some games
        ; read ROM bytes as data (Piton 3 reads CD21-CD28)
        INCLUDE "overview.asm"
        ASSERT $ <= 0CD21h, "code runs into CD21 (Monitor-2 bytes read by games)"
        ; the rest up to CE1D: Monitor-2's bytes
        INCBIN "../base/spec_m2.rom", $-0C000h, 0CE1Dh-$
        ASSERT $ == 0CE1Dh
; C818: print string (HL) up to 00
M_PUTS: MOV A,M
        MOV C,A
        CPI 00h
        RZ
        CALL M_PUTC
        INX H
        JMP M_PUTS

        ASSERT $ <= 0CE5Ch
        ORG 0CE5Ch
; C812 / C81B: code of the pressed key or FF, without waiting (uses the base scanner)
M_KEYSTAT:
        CALL .scan
        PUSH PSW
        CALL 0C254h
        POP PSW
        RET
.scan:  PUSH H
        PUSH B
        CALL 0C25Ah
        LDA PPI_A
        CPI 0FFh
        JNZ .a
        LDA PPI_C
        ORI 0F0h
        CPI 0FFh
        JZ 0C248h
        MOV H,A
        MVI L,0FFh
        JMP .b
.a:     MOV L,A
        MVI H,0FFh
.b:     MVI C,0FBh
.c:     INR C
        DAD H
        JC .c
        MOV L,C
        CALL 0C254h
        LDA PPI_B
        ORI 03h
        CPI 0FFh
        JNZ 0C235h
        JMP 0C248h

        ASSERT $ == 0CE9Dh
; C815: print A as two hex digits
M_PUTHEX:
        PUSH D
        PUSH B
        CALL HEX2
        MOV C,D
        CALL M_PUTC
        MOV C,E
        CALL M_PUTC
        POP B
        POP D
        RET

; A -> D, E: ASCII hex digits of the high and low nibble
HEX2:   MOV E,A
        RRC
        RRC
        RRC
        RRC
        CALL HEXDIG
        MOV D,A
        MOV A,E
        CALL HEXDIG
        MOV E,A
        RET
HEXDIG: ANI 0Fh
        CPI 0Ah
        JC .d
        ADI 07h
.d:     ADI 30h
        RET

        ASSERT $ == 0CEC8h
; C809: print character C; scrolls the monitor text area by one 10-pixel line at the bottom
M_PUTC:
        PUSH PSW
        MOV A,C
        CPI 07h
        CZ 0C170h               ; bell
        CPI 20h
        JC .ctl
        PUSH B
        MVI C,7Fh               ; printable: clear the cell first, then step back
        CALL .out
        MVI C,08h
        CALL .out
        POP B
.ctl:   CALL .out
        POP PSW
        RET
.out:   LDA V_CURSOR
        CPI 0F8h
        CNC .bottom
        CALL B_PUTC
        RET
.bottom:
        MOV A,C
        CPI 0Ah
        JZ M_SCROLL
        CPI 1Ah
        JZ M_SCROLL
        LDA V_CURSOR+1
        CPI 0BEh
        RC
M_SCROLL:
        PUSH H
        PUSH D
        PUSH B
        LXI H,SCREEN
        LXI D,SCREEN+0Ah
        MVI B,30h
.col:   MVI C,78h
.cp:    LDAX D
        MOV M,A
        INX H
        INX D
        LDAX D
        MOV M,A
        INX H
        INX D
        DCR C
        JNZ .cp
        LDA V_FILL
        MVI C,10h
.fl:    MOV M,A
        INX H
        INX D
        DCR C
        JNZ .fl
        DCR B
        JNZ .col
        LXI H,00F8h
        SHLD V_CURSOR
        POP B
        POP D
        POP H
        MOV A,C
        CPI 0Ah
        RNZ
        MVI C,1Ah
        RET

        ASSERT $ == 0CF3Bh

; C824: read a block from tape (start, end, data, checksum), as Monitor-2 does
M_TAPE_RD:
        MOV A,L
        ORA A
        JZ .r
        STA 8FFFh               ; tape speed constant
.r:     CALL 0C3F9h             ; base: start, end, data -> (8FE3) .. DE
        MVI A,08h
        CALL B_TAPE_IN
        MOV L,A
        MVI A,08h
        CALL B_TAPE_IN
        MOV H,A
        SHLD 8F8Ah              ; checksum from tape
        XCHG
        SHLD 8F8Ch              ; end address
        XCHG
        LHLD 8FE3h
        CALL M_CHECKSUM
        LHLD 8F8Ah
        MOV E,C
        MOV D,B
        CALL B_CMP_HL_DE
        JZ .ok
        LXI H,S_TAPE_ERR
        CALL M_PUTS
.ok:    PUSH H
        LXI H,PPI_CTL
        MVI M,0Dh
        MVI M,0Ch
        POP H
        RET
S_TAPE_ERR: DB 0Ah,0Dh,"? CHECKSUM",0
