; =====================================================================================
;  8080 disassembler and one-line assembler (devel ROM).
;  Mnemonics are numbered so that the operand format follows from the number (FMTB):
;    0 none   1 r (bits 5-3)   2 rp   3 rp,d16   4 r,d8   5 d8   6 a16   7 B/D   8 rp/PSW
;    9 RST n  10 r (bits 2-0, ADD..CMP)   11 MOV r,r
;  The assembler tries all 256 opcodes and takes the first whose mnemonic and operands match
;  the line, so it accepts exactly what the disassembler prints (undocumented opcodes are
;  printed as their documented twins and never chosen, because the documented ones come first).
; =====================================================================================

M_HLT   EQU 9
M_ALU   EQU 69                  ; ADD; ADC..CMP follow
M_MOV   EQU 77

FMTB:   DB 25, 27, 30, 31, 32, 42, 64, 66, 68, 69, 77, 78

; register names, 4 bytes each: B C D E H L M A SP PSW
REGS:   DB 'B',0,0,0, 'C',0,0,0, 'D',0,0,0, 'E',0,0,0
        DB 'H',0,0,0, 'L',0,0,0, 'M',0,0,0, 'A',0,0,0
        DB 'S','P',0,0, 'P','S','W',0

        INCLUDE "distab.inc"

; A = opcode -> C = mnemonic, A = format, B = length. Keeps DE, HL.
DECODE: MOV B,A
        CPI 76h
        MVI C,M_HLT
        JZ .fmt
        CPI 40h
        JC .tab
        CPI 80h
        MVI C,M_MOV
        JC .fmt
        CPI 0C0h
        JNC .tab2
        RRC                     ; 80-BF: ADD..CMP
        RRC
        RRC
        ANI 7
        ADI M_ALU
        MOV C,A
        JMP .fmt
.tab2:  SUI 80h
.tab:   PUSH H
        MOV C,A
        MVI B,0
        LXI H,OPTAB
        DAD B
        MOV C,M
        POP H
.fmt:   PUSH H
        LXI H,FMTB
        MVI B,0
.f:     MOV A,C
        CMP M
        JC .got
        INX H
        INR B
        JMP .f
.got:   MOV A,B
        POP H
        MVI B,3                 ; length: 3 for formats 3 and 6, 2 for 4 and 5
        CPI 3
        RZ
        CPI 6
        RZ
        DCR B
        CPI 4
        RZ
        CPI 5
        RZ
        DCR B
        RET

; register name A (0..9) -> HL points to it. Keeps BC, DE.
REGPTR: ADD A
        ADD A
        ADI LOW(REGS)
        MOV L,A
        MVI A,HIGH(REGS)
        ACI 0
        MOV H,A
        RET

; name of register A at HL (buffer). Keeps BC, DE.
BREG:   PUSH D
        PUSH H
        CALL REGPTR
        XCHG
        POP H
        CALL BSTR
        POP D
        RET

; mnemonic C's name at HL (buffer). Keeps BC, DE.
BMNEM:  PUSH D
        PUSH B
        LXI D,MNAMES
        INR C
.skip:  DCR C
        JZ .out
.s2:    LDAX D
        INX D
        ORA A
        JP .s2
        JMP .skip
.out:   LDAX D
        INX D
        MOV M,A
        INX H
        ORA A
        JP .out
        DCX H
        ANI 7Fh
        MOV M,A
        INX H
        POP B
        POP D
        RET

; ---------------------------------------------------------------- disassembler
; DE = address of a command, HL = text buffer -> the command as text at HL (no 00),
; DE = next command, B = length.
DISASM: LDAX D
        STA DOP
        CALL DECODE
        STA DFM
        MOV A,B
        STA DLEN
        CALL BMNEM
        LDA DFM
        ORA A
        JZ .done                ; format 0: no operands
        MVI M,' '
        INX H
        MOV B,A                 ; B = format
        LDA DOP
        MOV C,A                 ; C = opcode
        MOV A,B
        CPI 9
        JZ .rst
        CPI 10
        JZ .alu
        CPI 5
        JZ .d8
        CPI 6
        JZ .a16
        CPI 2
        JZ .pair
        CPI 3
        JZ .pair
        CPI 7
        JZ .bd
        CPI 8
        JZ .psw
        ; 1, 4, 11: r from bits 5-3
        MOV A,C
        RRC
        RRC
        RRC
        ANI 7
        CALL BREG
        MOV A,B
        CPI 4
        JZ .cd8
        CPI 11
        JNZ .done
        MVI M,','
        INX H
.alu:   MOV A,C
        ANI 7
        CALL BREG
        JMP .done
.rst:   MOV A,C
        RRC
        RRC
        RRC
        ANI 7
        ADI '0'
        MOV M,A
        INX H
        JMP .done
.bd:    MOV A,C                 ; STAX/LDAX: B or D
        ANI 10h
        RRC
        RRC
        RRC                     ; 0 or 2 = B or D
        CALL BREG
        JMP .done
.psw:   MVI B,9
        JMP .rp
.pair:  MVI B,8
.rp:    MOV A,C
        RRC
        RRC
        RRC
        ANI 6                   ; pair * 2 = B, D, H
        CPI 6
        JNZ .r
        MOV A,B                 ; pair 3: SP or PSW
.r:     CALL BREG
        MOV A,C
        ANI 0CFh
        CPI 01h                 ; LXI: then d16
        JNZ .done
        MVI M,','
        INX H
        JMP .a16
.cd8:   MVI M,','
        INX H
.d8:    INX D
        LDAX D
        DCX D
        CALL BHEX8
        JMP .done
.a16:   INX D
        INX D
        LDAX D
        CALL BHEX8
        DCX D
        LDAX D
        CALL BHEX8
        DCX D
.done:  LDA DLEN
        MOV B,A
        ADD E
        MOV E,A
        RNC
        INR D
        RET

; ---------------------------------------------------------------- one-line assembler
; DE -> line (00 at the end), HL = where to put the command. Carry if the line is wrong;
; else the bytes are written and B = their number. Uses ABUF.
ANAME   EQU ABUF                ; 6: mnemonic
AOP1    EQU ABUF+6              ; 8: first operand
AOP2    EQU ABUF+14             ; 8: second operand
AOPN    EQU ABUF+22             ; number of operands
AMN     EQU ABUF+23             ; mnemonic number
AOPC    EQU ABUF+24             ; opcode being tried
ADST    EQU ABUF+25             ; 2: where to put it
AVAL    EQU ABUF+27             ; 2: number of the operand
DOP     EQU ABUF+44             ; disassembler: opcode, format, length
DFM     EQU ABUF+45
DLEN    EQU ABUF+46

ASM1:   SHLD ADST
        LXI H,ANAME
        CALL .word              ; mnemonic: letters
        XRA A
        STA AOPN
        LXI H,AOP1
        CALL .opnd
        JZ .find
        LXI H,AOP2
        CALL .opnd
        LDAX D                  ; nothing may follow
        ORA A
        STC
        RNZ
        ; mnemonic number
.find:  MVI C,0
.n:     MOV A,C
        CPI 78
        CMC
        RC                      ; unknown mnemonic
        PUSH B
        LXI H,ABUF+28           ; (scratch) name of mnemonic C
        CALL BMNEM
        MVI M,0
        LXI H,ABUF+28
        LXI D,ANAME
        CALL STREQ
        POP B
        JZ .known
        INR C
        JMP .n
.known: MOV A,C
        STA AMN
        XRA A                   ; try every opcode
.try:   STA AOPC
        CALL DECODE
        MOV D,A                 ; format
        LDA AMN
        CMP C
        JNZ .next
        PUSH B
        MOV A,D
        CALL .match
        POP B
        JNC .emit
.next:  LDA AOPC
        INR A
        JNZ .try
        STC
        RET
.emit:  LHLD ADST
        LDA AOPC
        MOV M,A
        MOV A,B
        CPI 1
        RZ                      ; no carry: A >= 1
        INX H
        LDA AVAL
        MOV M,A
        MOV A,B
        CPI 2
        RZ
        INX H
        LDA AVAL+1
        MOV M,A
        ORA A
        RET

; one word of letters/digits, upper case, at HL (00 after); skips spaces before it
.word:  CALL .sp
.w:     LDAX D
        CALL UPCASE
        CPI ','
        JZ .we
        CPI ' '
        JZ .we
        ORA A
        JZ .we
        MOV M,A
        INX H
        INX D
        JMP .w
.we:    MVI M,0
        RET
.sp:    LDAX D
        CPI ' '
        RNZ
        INX D
        JMP .sp
; an operand into HL; NZ if there was one; then skips a comma
.opnd:  CALL .sp
        LDAX D
        ORA A
        RZ
        CALL .w
        LDA AOPN
        INR A
        STA AOPN
        CALL .sp
        CPI ','
        RNZ                     ; NZ: there was an operand
        INX D
        ORI 1                   ; NZ
        RET

; do the operands fit opcode AOPC of format A? carry if not; AVAL = the number
.match: MOV B,A                 ; B = format
        LDA AOPC
        MOV C,A                 ; C = opcode
        LDA AOPN
        MOV D,A                 ; D = operands given
        MOV A,B
        ORA A
        JNZ .m1
        MOV A,D                 ; format 0: none
        ORA A
        RZ
        STC
        RET
.m1:    CPI 11
        JZ .mmov
        CPI 3
        JZ .two
        CPI 4
        JZ .two
        MOV A,D                 ; the rest: one operand
        DCR A
        STC
        RNZ
        MOV A,B
        CPI 5
        JZ .num8
        CPI 6
        JZ .num
        CPI 9
        JZ .mrst
        CPI 10
        JZ .malu
        CPI 7
        JZ .mbd
        CPI 8
        JZ .mpsw
        CPI 2
        JZ .mpair
        ; 1: r bits 5-3
.mr53:  MOV A,C
        RRC
        RRC
        RRC
        ANI 7
        LXI D,AOP1
        JMP REGEQ
.two:   MOV A,D
        CPI 2
        STC
        RNZ
        MOV A,B
        CPI 4
        JZ .mmvi
        CALL .mpair             ; 3: LXI rp,d16
        RC
        LXI H,AOP2
        JMP .numh
.mmvi:  CALL .mr53
        RC
        LXI H,AOP2
        CALL .numh
        RC
        LDA AVAL+1
        ORA A
        RZ
        STC
        RET
.mmov:  MOV A,D
        CPI 2
        STC
        RNZ
        CALL .mr53
        RC
        MOV A,C
        ANI 7
        LXI D,AOP2
        JMP REGEQ
.malu:  MOV A,C
        ANI 7
        LXI D,AOP1
        JMP REGEQ
.mbd:   MOV A,C
        ANI 10h
        RRC
        RRC
        RRC
        LXI D,AOP1
        JMP REGEQ
.mpsw:  MVI E,9
        JMP .mp
.mpair: MVI E,8
.mp:    MOV A,C
        RRC
        RRC
        RRC
        ANI 6
        CPI 6
        JNZ .mq
        MOV A,E
.mq:    LXI D,AOP1
        JMP REGEQ
.mrst:  CALL .num
        RC
        LDA AVAL
        MOV D,A
        MOV A,C
        RRC
        RRC
        RRC
        ANI 7
        CMP D
        RZ
        STC
        RET
.num8:  CALL .num
        RC
        LDA AVAL+1
        ORA A
        RZ
        STC
        RET
.num:   LXI H,AOP1
; hex number at HL (an "H" may end it) -> AVAL; carry if it is not one. Keeps BC, DE.
.numh:  PUSH D
        PUSH B
        LXI D,0
        MVI B,0                 ; digits
.nl:    MOV A,M
        ORA A
        JZ .nend
        INX H
        CPI 'H'
        JNZ .nd
        MOV A,M                 ; H: only last, after a digit
        ORA A
        JNZ .bad
.nend:  MOV A,B
        ORA A
        JZ .bad
        XCHG
        SHLD AVAL
        POP B
        POP D
        ORA A
        RET
.nd:    CALL HEXVAL
        JC .bad
        XCHG
        DAD H
        DAD H
        DAD H
        DAD H
        ORA L
        MOV L,A
        XCHG
        INR B
        JMP .nl
.bad:   POP B
        POP D
        STC
        RET

; is the operand at DE register A's name? carry if not
REGEQ:  CALL REGPTR
; are the strings at HL and DE equal? Z/NC if yes, carry if not
STREQ:  LDAX D
        CMP M
        STC
        RNZ
        INX D
        INX H
        ORA A
        JNZ STREQ
        RET                     ; Z, carry clear (ORA)

; A = character -> Latin capital if it was a Latin small letter
UPCASE: CPI 0A1h
        RC
        CPI 0BBh
        RNC
        SUI 0A1h-41h
        RET
