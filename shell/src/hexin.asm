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

