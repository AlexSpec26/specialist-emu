; =====================================================================================
;  Debugger engine (ROM-003), shared by both ROMs: breakpoints, steps, run, stop.
;  The ROM provides DBG_SAY (show message HL) and DBG_STOP (after the program stopped, SP
;  is the shell's). Screen: dbgui.asm (shell), devel.asm (devel ROM).
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

DBG_SETPC:
        LXI H,0
        SHLD R_PC
DBG_SETSP:
        LXI H,DBG_DONE
        SHLD DSTACK
        LXI H,DSTACK
        SHLD R_SP
        RET

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
        JMP DBG_SAY
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
.stop:  JMP DBG_STOP              ; the ROM shows the stop its own way

; the program's last RET comes here (pushed by F2)
DBG_DONE:
        SHLD R_HL
        PUSH PSW
        MVI A,1
        STA DEND
        POP PSW
        LXI H,DBG_DONE
        JMP DBG_SAVE


; add a breakpoint at DE, or remove it if it is there. Carry if there are already 4.
DBG_TOGGLE:
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
        ORA A
        RET
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
        STC
        RET
.put:   MOV M,D
        DCX H
        MOV M,E
        ORA A
        RET
S_G_FULL: TEXT "точек уже 4",0
