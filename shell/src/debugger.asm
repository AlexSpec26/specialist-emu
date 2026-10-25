; =====================================================================================
;  «Отладка» (ROM-003). Была отложена (rom-build-6..9), возвращена 2026-10-25 вместо
;  «Монитора»: «попробуй для начала выкинуть монитор и вернуть отладчик».
; =====================================================================================

DBG_INIT:
        ; debugger: empty breakpoints, PC 0000, SP with a return into the debugger
        LXI H,DBP
        MVI B,8
.bp:    MVI M,0FFh
        INX H
        DCR B
        JNZ .bp
        LXI H,0
        SHLD R_AF
        SHLD R_BC
        SHLD R_DE
        SHLD R_HL
        XRA A
        STA DSEL
        STA DMODE
        STA DEND
        STA DPLN
        STA DVON
        JMP DBG_SETPC

; ---------------------------------------------------------------- debugger (ROM-003)
; The program runs on the real processor. Breakpoints (F8/F9) are CALL DBG_TRAPC written over
; 3 bytes (most programs load at 0000, so the RST vectors are their code). A single step puts
; RST 7 (FF) on the next command and on the branch target, with JMP DBG_TRAP at 0038-003A
; only for that one command. All changed bytes are saved and put back at the stop. Only for
; code in ОЗУ (0000-BFFF).
R_AF    EQU APPVARS+1Ah         ; registers of the program, in this order
R_BC    EQU APPVARS+1Ch
R_DE    EQU APPVARS+1Eh
R_HL    EQU APPVARS+20h
R_SP    EQU APPVARS+22h
R_PC    EQU APPVARS+24h
DSEL    EQU APPVARS+26h         ; selected register 0..5
DMODE   EQU APPVARS+27h         ; 1 = after this stop put the breakpoints and run on
DEND    EQU APPVARS+28h         ; 1 = the program returned into the debugger
DBP     EQU APPVARS+29h         ; 4 breakpoints, FFFF = none
DPLN    EQU APPVARS+31h         ; planted traps
DPL     EQU APPVARS+32h         ; 4 x (address, 3 saved bytes)
DVEC    EQU APPVARS+46h         ; saved 0038-003A
DN1     EQU APPVARS+49h         ; step: next address and branch target
DN2     EQU APPVARS+4Bh
DVON    EQU APPVARS+4Dh         ; 1 = the vector at 0038 is ours
DKIND   EQU APPVARS+4Eh         ; what DBG_PLANT writes: 0 RST 7, 1 CALL DBG_TRAPC
DSTACK  EQU 8DFEh               ; program stack after F2; holds the return into the debugger

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
        MOV C,A                 ; names never cross a page (ASSERT below)
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
        ASSERT HIGH(S_G_NAMES) == HIGH($), "S_G_NAMES crosses a page"
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

; Z if DE is a breakpoint
DBG_ISBP:
        PUSH H
        PUSH B
        LXI H,DBP
        MVI B,4
.l:     MOV A,M
        INX H
        CMP E
        JNZ .n
        MOV A,M
        CMP D
        JZ .x
.n:     INX H
        DCR B
        JNZ .l
        INR B                   ; NZ
.x:     POP B
        POP H
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

; A = opcode -> B = length, C = 0 plain, 1 jump, 2 call, 3 return, 4 PCHL, 5 RST.
; Keeps A, DE, HL.
OPINFO: PUSH D
        MOV D,A
        MVI B,1
        MVI C,0
        ANI 0C7h
        CPI 06h                 ; MVI
        JZ .two
        CPI 0C6h                ; ADI ... CPI
        JZ .two
        CPI 0C2h                ; Jcc
        JZ .jmp
        CPI 0C4h                ; Ccc
        JZ .call
        CPI 0C0h                ; Rcc
        JZ .ret
        CPI 0C7h                ; RST
        JZ .rst
        MOV A,D
        ANI 0CFh
        CPI 01h                 ; LXI
        JZ .three
        MOV A,D
        CPI 0C3h
        JZ .jmp
        CPI 0CDh
        JZ .call
        CPI 0C9h
        JZ .ret
        CPI 0E9h
        JZ .pchl
        CPI 0D3h                ; OUT
        JZ .two
        CPI 0DBh                ; IN
        JZ .two
        ANI 0E7h
        CPI 22h                 ; SHLD LHLD STA LDA
        JZ .three
        JMP .x
.two:   MVI B,2
        JMP .x
.jmp:   MVI C,1
        JMP .three
.call:  MVI C,2
.three: MVI B,3
        JMP .x
.ret:   MVI C,3
        JMP .x
.pchl:  MVI C,4
        JMP .x
.rst:   MVI C,5
.x:     MOV A,D
        POP D
        RET

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
DBG_SETPC:
        LXI H,0
        SHLD R_PC
DBG_SETSP:
        LXI H,DBG_DONE
        SHLD DSTACK
        LXI H,DSTACK
        SHLD R_SP
        RET

; F8: add a breakpoint, or remove it if it is there
DBG_BREAK:
        LXI H,S_G_BP
        CALL MEM_ASK
        JC MEM_CLRMSG
        XCHG
        CALL DBG_ISBP
        JNZ .add
        LXI H,DBP               ; remove: find it again
.f:     MOV A,M
        INX H
        CMP E
        JNZ .f2
        MOV A,M
        CMP D
        JZ .rm
.f2:    INX H
        JMP .f
.rm:    MVI M,0FFh
        DCX H
        MVI M,0FFh
        JMP .done
.add:   LXI H,DBP
        MVI B,4
.a:     MOV A,M
        INX H
        ANA M
        INR A
        JZ .put
        INX H
        DCR B
        JNZ .a
        LXI H,S_G_FULL
        JMP MEM_MSG
.put:   MOV M,D
        DCX H
        MOV M,E
.done:  CALL MEM_CLRMSG
        CALL DBG_CODE
        JMP DBG_SIDE
S_G_BP:   TEXT "точка останова (есть - снять): ",0
S_G_FULL: TEXT "точек уже 4",0

; F5 / F6 (A = 1: do not go into CALL / RST)
DBG_STEP:
        STA DN2                 ; temporarily: the "over" flag
        LHLD R_PC
        MOV A,H
        CPI 0C0h
        JNC .inrom
        MOV A,M
        CALL OPINFO
        PUSH H
        MOV E,B
        MVI D,0
        DAD D
        SHLD DN1                ; next command
        POP H
        LDA DN2
        MOV B,A                 ; B = over
        MOV A,C
        ORA A
        JZ .none
        CPI 3
        JZ .ret
        CPI 4
        JZ .pchl
        CPI 5
        JZ .rst
        INX H                   ; jump or call: the address in the command
        MOV E,M
        INX H
        MOV D,M
        CPI 2
        JNZ .chk
.callx: MOV A,B                 ; call: maybe step over; a call into ПЗУ is always stepped over
        ORA A
        JNZ .none
        MOV A,D
        CPI 0C0h
        JNC .none
        JMP .set
.ret:   LHLD R_SP
        MOV E,M
        INX H
        MOV D,M
        JMP .chk
.pchl:  LHLD R_HL
        XCHG
        JMP .chk
.rst:   MOV A,M
        ANI 38h
        MOV E,A
        MVI D,0
        JMP .callx
.chk:   MOV A,D
        CPI 0C0h
        JC .set
        CPI HIGH(DBG_DONE)      ; the last RET of the program: DBG_DONE stops by itself
        JNZ .rom
        MOV A,E
        CPI LOW(DBG_DONE)
        JZ .none
.rom:   LXI H,S_G_TOROM
        JMP DBG_REFUSE
.none:  LXI D,0FFFFh
.set:   XCHG
        SHLD DN2
        LHLD R_PC               ; the vector 0038-003A must not be touched by this command
        LXI D,-36h
        CALL .near
        LHLD DN1
        LXI D,-38h
        CALL .near
        LHLD DN2
        LXI D,-38h
        CALL .near
        CALL DBG_VEC
        XRA A
        STA DKIND
        LHLD DN1
        CALL DBG_PLANT
        LHLD DN2
        CALL DBG_PLANT
        JMP DBG_GO
.near:  DAD D                   ; HL - start < 5 / 3: too close to 0038
        MOV A,H
        ORA A
        RNZ
        MOV A,L
        CPI 5
        RNC
        POP H                   ; drop the return: refuse the step
        LXI H,S_G_VEC
        JMP DBG_REFUSE
.inrom: LXI H,S_G_PCROM
DBG_REFUSE:
        XRA A
        STA DMODE
        JMP MEM_MSG
S_G_TOROM: TEXT "переход в ПЗУ: шаг нельзя, поставьте точку и F9",0
S_G_PCROM: TEXT "PC в ПЗУ: шаг нельзя",0

; F9: run to a breakpoint (if PC is on one, first one step)
DBG_RUN:
        LHLD R_PC
        XCHG
        CALL DBG_ISBP
        JNZ .go
        MVI A,1
        STA DMODE
        XRA A
        JMP DBG_STEP
.go:    CALL DBG_PLANTBP
        JMP DBG_GO

DBG_PLANTBP:
        MVI A,1
        STA DKIND
        LXI D,DBP
        MVI B,4
.l:     LDAX D
        MOV L,A
        INX D
        LDAX D
        MOV H,A
        INX D
        PUSH D
        CALL DBG_PLANT
        POP D
        DCR B
        JNZ .l
        RET

; start the program with its registers
DBG_GO: LHLD R_SP
        SPHL
        LHLD R_PC
        PUSH H
        LHLD R_BC
        MOV B,H
        MOV C,L
        LHLD R_DE
        XCHG
        LHLD R_AF
        PUSH H
        POP PSW
        LHLD R_HL
        RET

; RST 7 lands here: the return address is the stop address + 1
DBG_TRAP:
        SHLD R_HL
        POP H
        DCX H
        JMP DBG_SAVE
; a breakpoint (CALL) lands here: the return address is the stop address + 3
DBG_TRAPC:
        SHLD R_HL
        POP H
        DCX H
        DCX H
        DCX H
DBG_SAVE:
        SHLD R_PC
        PUSH PSW
        POP H
        SHLD R_AF
        XCHG
        SHLD R_DE
        MOV H,B
        MOV L,C
        SHLD R_BC
        LXI H,0
        DAD SP
        SHLD R_SP
        LXI SP,STACK_TOP
        CALL DBG_UNPLANT
        LDA DMODE
        ORA A
        JZ .stop
        XRA A
        STA DMODE
        CALL DBG_PLANTBP
        JMP DBG_GO
.stop:  MVI A,3                 ; show the debugger (program 3)
        STA CURAPP
        LDA RUNMASK
        ORI 8
        STA RUNMASK
        JMP MAIN

; the program's last RET comes here (pushed by F2)
DBG_DONE:
        SHLD R_HL
        PUSH PSW
        MVI A,1
        STA DEND
        POP PSW
        LXI H,DBG_DONE
        JMP DBG_SAVE

