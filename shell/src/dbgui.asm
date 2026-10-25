; =====================================================================================
;  «Отладка» in the shell ROM: the screen and keys over the engine in dbgcore.asm.
; =====================================================================================
DBG_SAY EQU MEM_MSG

DBG_DRAW:
        CALL PRINTL
        DB 36,4
        TEXT "Стек",0
        DB 4,4
        TEXT "Код от PC (",01h,"►",02h," PC, * точка)",0
        DB 4,24
        TEXT "◄► регистр  ВК изменить  F2 PC=адрес  F8 точка останова",0
        DB 4,25
        TEXT "F5 шаг  F6 шаг без захода в CALL  F9 пуск до точки",0
        DB 0FFh
        CALL DBG_REGS
        CALL DBG_CODE
        CALL DBG_SIDE
        LDA DEND
        ORA A
        RZ
        XRA A
        STA DEND
        LXI H,S_G_END
        JMP MEM_MSG

; row 2: registers, the selected one inverted; row 3: flags
DBG_REGS:
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        MVI M,' '
        INX H
        LXI D,R_AF
        MVI B,0
.r:     MOV A,B
        PUSH B
        LXI B,S_G_NAMES
        ADD A
        ADD C
        MOV C,A
        MOV A,B
        ACI 0
        MOV B,A
        LDAX B
        MOV M,A
        INX H
        INX B
        LDAX B
        MOV M,A
        INX H
        MVI M,'='
        INX H
        POP B
        LDA DSEL
        CMP B
        JNZ .n1
        MVI M,01h
        INX H
.n1:    CALL BHEXW
        LDA DSEL
        CMP B
        JNZ .n2
        MVI M,02h
        INX H
.n2:    MVI M,' '
        INX H
        MVI M,' '
        INX H
        INR B
        MOV A,B
        CPI 6
        JNZ .r
        MVI C,65                ; 63 columns + the two inverse codes
        CALL BPADTO
        MVI B,0
        MVI C,2
        CALL BPRINT
        ; flags S Z AC P C: letter when set
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        LXI D,S_G_FLAGS
        CALL BSTR
        LDA R_AF
        MOV B,A
        LXI D,S_G_FL
.f:     LDAX D
        ORA A
        JZ .fe
        MOV C,A                 ; bit mask
        INX D
        LDAX D
        INX D
        MOV M,A
        MOV A,B
        ANA C
        JNZ .fs
        MVI M,C_DOT
.fs:    INX H
        MVI M,' '
        INX H
        JMP .f
.fe:    MVI C,32
        CALL BPADTO
        MVI B,0
        MVI C,3
        JMP BPRINT
S_G_NAMES: TEXT "AFBCDEHLSPPC"
S_G_FLAGS: TEXT "  флаги: ",0
S_G_FL: DB 80h,'S',40h,'Z',10h,'A',04h,'P',01h,'C',0

; rows 5..20: 16 commands from PC as bytes
DBG_CODE:
        LHLD R_PC
        XCHG
        MVI C,5
.l:     PUSH B
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        MVI M,' '
        INX H
        LHLD R_PC
        MOV A,L
        CMP E
        JNZ .np
        MOV A,H
        CMP D
        JNZ .np
        LXI H,LBUF+2
        MVI M,0FBh              ; ►
        JMP .bp
.np:    LXI H,LBUF+2
        MVI M,' '
.bp:    INX H
        CALL DBG_ISBP
        MVI M,' '
        JNZ .nb
        MVI M,'*'
.nb:    INX H
        CALL BHEX16
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        LDAX D
        CALL OPINFO
.b:     LDAX D
        CALL BHEX8
        MVI M,' '
        INX H
        INX D
        DCR B
        JNZ .b
        MVI C,32
        CALL BPADTO
        POP B
        PUSH B
        MVI B,0
        PUSH D
        CALL BPRINT
        POP D
        POP B
        INR C
        MOV A,C
        CPI 21
        JNZ .l
        RET

; right side: stack (8 words), memory at HL, breakpoints
DBG_SIDE:
        LHLD R_SP
        XCHG
        MVI C,5
.s:     LXI H,LBUF
        CALL BHEX16
        MVI M,':'
        INX H
        MVI M,' '
        INX H
        CALL BHEXW
        MVI B,36
        PUSH D
        CALL BPRINT
        POP D
        INR C
        MOV A,C
        CPI 13
        JNZ .s
        LXI H,LBUF
        LXI D,S_G_HL
        CALL BSTR
        MVI B,36
        MVI C,14
        CALL BPRINT
        LHLD R_HL
        XCHG
        LXI H,LBUF
        MVI B,8
.m:     LDAX D
        CALL BHEX8
        MVI M,' '
        INX H
        INX D
        DCR B
        JNZ .m
        MVI B,36
        MVI C,15
        CALL BPRINT
        LXI H,LBUF
        LXI D,S_G_BPS
        CALL BSTR
        MVI B,36
        MVI C,17
        CALL BPRINT
        LXI H,LBUF
        LXI D,DBP
        MVI B,4
.p:     LDAX D
        MOV C,A
        INX D
        LDAX D
        DCX D
        ANA C
        INR A
        JNZ .ph
        MVI M,'-'               ; empty
        INX H
        MVI M,'-'
        INX H
        MVI M,'-'
        INX H
        MVI M,'-'
        INX H
        JMP .pn
.ph:    CALL BHEXW
        DCX D
        DCX D
.pn:    MVI M,' '
        INX H
        INX D
        INX D
        DCR B
        JNZ .p
        MVI B,36
        MVI C,18
        JMP BPRINT
S_G_HL:  TEXT "(HL):",0
S_G_BPS: TEXT "Точки останова:",0
S_G_END: TEXT "программа вернулась в отладку (RET)",0

; ---------------------------------------------------------------- debugger: keys
DBG_KEY:
        LDA TMP
        CPI 18h                 ; →
        JZ .next
        CPI 08h                 ; ←
        JZ .prev
        CPI 0Dh
        JZ DBG_EDIT
        CPI 0C2h                ; F2
        JZ DBG_GO_ADDR
        CPI 0C5h                ; F5
        JZ .step
        CPI 0C6h                ; F6
        JZ .over
        CPI 0C8h                ; F8
        JZ DBG_BREAK
        CPI 0C9h                ; F9
        JZ DBG_RUN
        RET
.next:  LDA DSEL
        INR A
        CPI 6
        JC .sel
        XRA A
        JMP .sel
.prev:  LDA DSEL
        DCR A
        JP .sel
        MVI A,5
.sel:   STA DSEL
        JMP DBG_REGS
.step:  XRA A
        JMP DBG_STEP
.over:  MVI A,1
        JMP DBG_STEP

; ВК: new value of the selected register
DBG_EDIT:
        LXI H,S_G_VAL
        CALL MEM_ASK
        JC MEM_CLRMSG
        XCHG
        LDA DSEL
        ADD A
        MOV C,A
        MVI B,0
        LXI H,R_AF
        DAD B
        MOV M,E
        INX H
        MOV M,D
        CALL MEM_CLRMSG
        CALL DBG_REGS
        CALL DBG_CODE
        JMP DBG_SIDE
S_G_VAL: TEXT "новое значение: ",0

; F2: PC = address, SP = DSTACK with a return into the debugger
DBG_GO_ADDR:
        LXI H,S_M_GO
        CALL MEM_ASK
        JC MEM_CLRMSG
        SHLD R_PC
        CALL MEM_CLRMSG
        CALL DBG_SETSP
        CALL DBG_REGS
        CALL DBG_CODE
        JMP DBG_SIDE
; F8: add a breakpoint, or remove it if it is there
DBG_BREAK:
        LXI H,S_G_BP
        CALL MEM_ASK
        JC MEM_CLRMSG
        XCHG
        CALL DBG_TOGGLE
        LXI H,S_G_FULL
        JC MEM_MSG
        CALL MEM_CLRMSG
        CALL DBG_CODE
        JMP DBG_SIDE
S_G_BP:   TEXT "точка останова (есть - снять): ",0

; the program stopped: show «Отладка» (program 3)
DBG_STOP:
        MVI A,3
        STA CURAPP
        LDA RUNMASK
        ORI 8
        STA RUNMASK
        JMP MAIN
