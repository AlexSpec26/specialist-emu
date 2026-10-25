; =====================================================================================
;  Devel ROM: one screen. Row 0: registers and flags (inverted); row 1: the command at PC;
;  rows 2..29: console (top-down, wraps; the row after the newest line is kept empty);
;  row 31: the command line. Commands as in Monitor-2 (numbers hex, separated by spaces or
;  commas) plus U (disassemble), A (assemble), Z (step), B (breakpoint).
;  F5 = one step, F6 = step over a CALL, F9 = run to a breakpoint.
; =====================================================================================
DV      EQU APPVARS             ; APPVARS+1Ah.. belongs to the debugger engine
ORow    EQU DV+0                ; next console row
CMDLEN  EQU DV+1                ; characters typed
DLAST   EQU DV+2                ; 2: next address for D, L, U
DEND2   EQU DV+4                ; 2: end of a listing
LMODE   EQU DV+6                ; line mode: 0 commands, 1 assemble (A), 2 modify (M)
LADDR   EQU DV+7                ; 2: address for A and M
ZCOUNT  EQU DV+9                ; 2: steps still to do
ZOVER   EQU DV+0Bh              ; 1 = steps go over CALLs
CMDBUF  EQU 8D80h               ; 48 bytes: the line being typed
CMDMAX  EQU 46
OROW0   EQU 2
OROWN   EQU 30                  ; one past the last console row
PROW    EQU 31

DEV_INIT:
        LXI SP,STACK_TOP
        LXI H,B_INITVARS
        LXI D,B_INITVARS_END
        LXI B,8FDFh
        CALL B_COPY
        LXI H,0C800h
        SHLD V_WARM
        LXI H,7EFFh
        SHLD V_MEMTOP
        XRA A
        STA INV
        MVI A,0FFh
        STA LASTK
        LHLD SIG                ; warm start keeps registers and breakpoints
        MOV A,H
        CPI 0A5h
        JNZ .cold
        MOV A,L
        CPI 5Ah
        JZ .warm
.cold:  XRA A
        STA RUS
        LXI H,0A55Ah
        SHLD SIG
        LXI H,0
        SHLD DLAST
        CALL DBG_INIT
.warm:  XRA A
        STA CMDLEN
        STA LMODE
        STA ZCOUNT
        STA ZCOUNT+1
        CALL CLS
        MVI A,OROW0
        STA ORow
        LXI D,S_V_HELLO
        CALL CON_SAY
DEV_LOOP:
        LXI SP,STACK_TOP
        CALL DEV_REGS
        CALL DEV_PROMPT
.k:     CALL GETKEY
        CALL DEV_KEY
        JMP .k
S_V_HELLO: TEXT "Специалист: среда разработки.  ? - список команд",0

SHOW_LANG:
        RET

; ---------------------------------------------------------------- registers (rows 0-1)
DEV_REGS:
        LXI H,LBUF
        LXI D,R_AF
        MVI B,0
.r:     PUSH D
        LXI D,S_V_RN
        MOV A,B
        ADD A
        ADD E
        MOV E,A
        MVI A,0
        ADC D
        MOV D,A
        LDAX D
        MOV M,A
        INX H
        INX D
        LDAX D
        MOV M,A
        INX H
        MVI M,'='
        INX H
        POP D
        CALL BHEXW
        MVI M,' '
        INX H
        INR B
        MOV A,B
        CPI 6
        JNZ .r
        LDA R_AF                ; flags S Z A P C
        MOV B,A
        LXI D,S_V_FL
.f:     LDAX D
        ORA A
        JZ .fe
        MOV C,A
        INX D
        LDAX D
        INX D
        MOV M,A
        MOV A,B
        ANA C
        JNZ .fs
        MVI M,C_DOT
.fs:    INX H
        JMP .f
.fe:    MVI C,64
        CALL BPADTO
        CALL INVON
        MVI B,0
        MVI C,0
        CALL BPRINT
        CALL INVOFF
        ; row 1: the command at PC, and the word on the stack
        LXI H,LBUF
        MVI M,0FBh              ; ►
        INX H
        LHLD R_PC
        XCHG
        CALL CON_DIS
        MVI C,44
        CALL BPADTO
        PUSH H
        LXI H,S_V_SP
        XCHG
        POP H
        CALL BSTR
        PUSH H
        LHLD R_SP
        XCHG
        POP H
        CALL BHEXW
        MVI C,64
        CALL BPADTO
        MVI B,0
        MVI C,1
        JMP BPRINT
S_V_RN: TEXT "AFBCDEHLSPPC"
S_V_FL: DB 80h,'S',40h,'Z',10h,'A',04h,'P',01h,'C',0
S_V_SP: TEXT "(SP)=",0

; one listing line for the command at DE into HL: "AAAA  hh hh hh  MNEMONIC"; DE = next
CON_DIS:
        CALL BHEX16
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        PUSH H
        LDAX D
        CALL DECODE             ; B = length
        POP H
        PUSH D
.b:     LDAX D
        CALL BHEX8
        MVI M,' '
        INX H
        INX D
        DCR B
        JNZ .b
        POP D
        MVI C,6+3*3+1           ; mnemonic at column 16 of the line
        MOV A,L
        SUI LOW(LBUF)
        PUSH H
.p:     POP H
        MOV A,L
        SUI LOW(LBUF)
        CPI 17
        JNC .dis
        MVI M,' '
        INX H
        PUSH H
        JMP .p
.dis:   JMP DISASM

; ---------------------------------------------------------------- console
; output line: LBUF+2 .. HL; the next row is cleared; the row wraps to the top
CON_NL: MVI C,64
        CALL BPADTO
        MVI M,0
        LXI H,LBUF
        MVI M,' '
        INX H
        MVI M,' '
        LDA ORow
        MOV C,A
        MVI B,0
        CALL BPRINT
        LDA ORow
        INR A
        CPI OROWN
        JC .ok
        MVI A,OROW0
.ok:    STA ORow
        MOV C,A
        MVI B,0
        CALL GOTOXY
        MVI A,' '
        MVI B,64
        JMP REPCH

; the string DE as a console line
CON_SAY:
        LXI H,LBUF+2
        CALL BSTR
        JMP CON_NL

DBG_SAY:                        ; (engine) message HL
        XCHG
        JMP CON_SAY

; the command line: "> text_" (or "AAAA: text_" in A/M mode)
DEV_PROMPT:
        LXI H,LBUF
        LDA LMODE
        ORA A
        JZ .cmd
        PUSH H
        LHLD LADDR
        XCHG
        POP H
        CALL BHEX16
        LDA LMODE
        CPI 2
        JNZ .a
        MVI M,' '               ; M: the byte there now
        INX H
        LDAX D
        CALL BHEX8
.a:     MVI M,':'
        JMP .t
.cmd:   MVI M,'>'
.t:     INX H
        MVI M,' '
        INX H
        LXI D,CMDBUF
        LDA CMDLEN
        MOV B,A
        INR B
.c:     DCR B
        JZ .e
        LDAX D
        MOV M,A
        INX H
        INX D
        JMP .c
.e:     MVI M,'_'
        INX H
        MVI C,64
        CALL BPADTO
        MVI B,0
        MVI C,PROW
        JMP BPRINT

; ---------------------------------------------------------------- keys
DEV_KEY:
        CPI 0C5h                ; F5: one step
        JZ .f5
        CPI 0C6h                ; F6: step over
        JZ .f6
        CPI 0C9h                ; F9: run
        JZ DBG_RUN
        CPI 1Bh                 ; АР2: leave A/M mode, clear the line
        JZ .esc
        CPI 0Dh
        JZ DEV_ENTER
        CPI 08h
        JZ .bs
        CPI 20h
        RC
        CPI 0C0h
        JC .ch
        CPI 0E0h
        RC
.ch:    MOV B,A
        LDA CMDLEN
        CPI CMDMAX
        RNC
        MOV E,A
        INR A
        STA CMDLEN
        MVI D,0
        LXI H,CMDBUF
        DAD D
        MOV M,B
        JMP DEV_PROMPT
.bs:    LDA CMDLEN
        ORA A
        RZ
        DCR A
        STA CMDLEN
        JMP DEV_PROMPT
.esc:   XRA A
        STA LMODE
        STA CMDLEN
        JMP DEV_PROMPT
.f5:    LXI H,1
        XRA A
        JMP DEV_STEPS
.f6:    LXI H,1
        MVI A,1
        JMP DEV_STEPS

; ВК: a command, or a line of A / M
DEV_ENTER:
        LXI H,CMDBUF            ; the line ends with 00
        LDA CMDLEN
        MOV E,A
        MVI D,0
        DAD D
        MVI M,0
        XRA A
        STA CMDLEN
        LDA LMODE
        CPI 1
        JZ DEV_ALINE
        CPI 2
        JZ DEV_MLINE
        ; echo "> command"
        LXI H,LBUF+2
        MVI M,'>'
        INX H
        MVI M,' '
        INX H
        LXI D,CMDBUF
        CALL BSTR
        CALL CON_NL
        CALL DEV_PROMPT
        LXI D,CMDBUF
        CALL V_SKIP
        ORA A
        RZ
        INX D
        CALL UPCASE
        LXI H,CMDTAB
.find:  MOV C,A
        MOV A,M
        ORA A
        JZ V_ERR
        CMP C
        INX H
        JZ .go
        INX H
        INX H
        MOV A,C
        JMP .find
.go:    MOV A,M
        INX H
        MOV H,M
        MOV L,A
        PCHL

CMDTAB: DB 'D'
        DW C_D
        DB 'L'
        DW C_L
        DB 'U'
        DW C_U
        DB 'M'
        DW C_M
        DB 'A'
        DW C_A
        DB 'F'
        DW C_F
        DB 'T'
        DW C_T
        DB 'C'
        DW C_C
        DB 'S'
        DW C_S
        DB 'N'
        DW C_N
        DB 'K'
        DW C_K
        DB 'H'
        DW C_H
        DB 'G'
        DW C_G
        DB 'X'
        DW C_X
        DB 'Z'
        DW C_Z
        DB 'B'
        DW C_B
        DB 'R'
        DW C_R
        DB 'I'
        DW C_R
        DB 'W'
        DW C_W
        DB 'O'
        DW C_O
        DB '?'
        DW C_HELP
        DB 0

V_ERR:  LXI D,S_V_ERR
        JMP CON_SAY
V_ROM:  LXI D,S_V_ROM
        JMP CON_SAY
V_OK:   LXI D,S_V_OK
        JMP CON_SAY
S_V_ERR: TEXT "? команда или числа (? - список)",0
S_V_ROM: TEXT "? только ОЗУ 0000-BFFF",0
S_V_OK:  TEXT "готово",0

C_HELP: LXI D,S_V_H1
        CALL CON_SAY
        LXI D,S_V_H2
        CALL CON_SAY
        LXI D,S_V_H3
        CALL CON_SAY
        LXI D,S_V_H4
        JMP CON_SAY
S_V_H1: TEXT "D а,к дамп  L а,к текст  U а,к дизасс.  M а правка  A а ассемблер",0
S_V_H2: TEXT "F а,к,б заполн. T а,к,куда  C а,к,а2 сравн.  S а,к,бб.. поиск  N а,к,б",0
S_V_H3: TEXT "K а,к сумма  H а,б  G [а] пуск  X рег знач  Z [n] шаг  B [а] точка",0
S_V_H4: TEXT "R/I чтение  W а,к запись  O а,к,имя запись с именем   F5 F6 F9",0

; ---------------------------------------------------------------- parsing
; DE -> first character that is not a space or a comma, in A
V_SKIP: LDAX D
        CPI ' '
        JZ .n
        CPI ','
        RNZ
.n:     INX D
        JMP V_SKIP

; hex number at DE -> HL; carry if there is none
V_HEX:  CALL V_SKIP
        LXI H,0
        MVI B,0
.l:     LDAX D
        CALL UPCASE
        CALL HEXVAL
        JC .e
        DAD H
        DAD H
        DAD H
        DAD H
        ORA L
        MOV L,A
        INX D
        INR B
        JMP .l
.e:     MOV A,B
        CPI 1
        RET

; two numbers -> HL first, DE second (BC kept as is); carry if missing
V_TWO:  CALL V_HEX
        RC
        PUSH H
        CALL V_HEX
        XCHG
        POP H
        RET

; a line of 8 bytes: "AAAA: hh hh .. hh  tttttttt"; HL = address -> HL + 8
V_DLINE:
        XCHG
        LXI H,LBUF+2
        CALL BHEX16
        MVI M,':'
        INX H
        MVI B,8
.b:     MVI M,' '
        INX H
        LDAX D
        CALL BHEX8
        INX D
        DCR B
        JNZ .b
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        MOV A,E
        SUI 8
        MOV E,A
        MOV A,D
        SBI 0
        MOV D,A
        MVI B,8
.t:     LDAX D
        CALL V_CH
        MOV M,A
        INX H
        INX D
        DCR B
        JNZ .t
        PUSH D
        CALL CON_NL
        POP H
        RET

; A = byte -> a character to show (control codes as ·)
V_CH:   CPI 20h
        RNC
        MVI A,C_DOT
        RET

; start [end] for D, L, U: DLAST, DEND2 (end = start + A if not given); carry if none at all
V_RANGE:
        PUSH PSW
        CALL V_HEX
        JC .cont
        SHLD DLAST
        CALL V_HEX
        JNC .end
.cont:  POP PSW
        LHLD DLAST
        MOV E,A
        MVI D,0
        DAD D
        JMP .set
.end:   POP PSW
.set:   SHLD DEND2
        RET

; continue a listing? (next address in HL, end in DEND2): carry = stop (end, wrap or a key)
V_MORE: SHLD DLAST
        XCHG
        LHLD DEND2
        CALL SUB16
        RC
        MOV A,D
        ORA E
        STC
        RZ
        CALL KSCAN
        CPI 0FFh
        RZ                      ; no key: go on (Z, no carry)
        STC
        RET

; ---------------------------------------------------------------- D, L, U
C_D:    MVI A,7Fh
        CALL V_RANGE
.l:     LHLD DLAST
        CALL V_DLINE
        CALL V_MORE
        JNC .l
        RET

C_L:    MVI A,0FFh
        CALL V_RANGE
.l:     LHLD DLAST
        XCHG
        LXI H,LBUF+2
        CALL BHEX16
        MVI M,':'
        INX H
        MVI M,' '
        INX H
        MVI B,32
.c:     LDAX D
        CALL V_CH
        MOV M,A
        INX H
        INX D
        DCR B
        JNZ .c
        PUSH D
        CALL CON_NL
        POP H
        CALL V_MORE
        JNC .l
        RET

C_U:    CALL V_SKIP             ; no address: from PC
        ORA A
        JNZ .a
        LHLD R_PC
        SHLD DLAST
.a:     MVI A,30h
        CALL V_RANGE
.l:     LHLD DLAST
        XCHG
        LXI H,LBUF+1
        CALL DBG_ISBP           ; * before a breakpoint
        MVI M,' '
        JNZ .nb
        MVI M,'*'
.nb:    INX H
        CALL CON_DIS
        PUSH D
        CALL CON_NL
        POP H
        CALL V_MORE
        JNC .l
        RET

; ---------------------------------------------------------------- M, A
; M addr [bytes]: with bytes write them; without: line mode, one byte a line
C_M:    CALL V_HEX
        JC V_ERR
        SHLD LADDR
        PUSH H
        CALL V_SKIP
        POP H
        ORA A
        JZ .mode
.l:     PUSH H
        CALL V_HEX
        MOV A,L
        POP H
        JC .show
        MOV C,A
        MOV A,H
        CPI 0C0h
        JNC V_ROM
        MOV M,C
        INX H
        JMP .l
.show:  LHLD LADDR
        JMP V_DLINE
.mode:  MVI A,2
        STA LMODE
        JMP DEV_PROMPT

; a line in M mode: hex = new byte, empty = keep it; then the next address
DEV_MLINE:
        LXI D,CMDBUF
        CALL V_SKIP
        ORA A
        JZ .next
        CALL V_HEX
        JC .bad
        MOV C,L
        LHLD LADDR
        MOV A,H
        CPI 0C0h
        JNC .rom
        MOV M,C
.next:  LHLD LADDR
        INX H
        SHLD LADDR
        JMP DEV_PROMPT
.rom:   CALL V_ROM
        JMP DEV_PROMPT
.bad:   CALL V_ERR
        JMP DEV_PROMPT

; A addr: line mode, one command a line, empty line ends
C_A:    CALL V_HEX
        JC V_ERR
        SHLD LADDR
        MVI A,1
        STA LMODE
        JMP DEV_PROMPT

DEV_ALINE:
        LXI D,CMDBUF
        CALL V_SKIP
        ORA A
        JZ .end
        LHLD LADDR
        MOV A,H
        CPI 0C0h
        JNC .rom
        CALL ASM1
        JC .bad
        LHLD LADDR              ; show what was assembled
        PUSH H
        XCHG
        LXI H,LBUF+2
        CALL CON_DIS
        XCHG
        SHLD LADDR
        POP H
        CALL CON_NL
        JMP DEV_PROMPT
.bad:   LXI H,LBUF+2            ; "? line"
        MVI M,'?'
        INX H
        MVI M,' '
        INX H
        LXI D,CMDBUF
        CALL BSTR
        CALL CON_NL
        JMP DEV_PROMPT
.rom:   CALL V_ROM
.end:   XRA A
        STA LMODE
        JMP DEV_PROMPT

; ---------------------------------------------------------------- F, T, C, S, N, K, H
; three numbers -> MCOPY, MCOPY+2, MCOPY+4 (the third may be missing: carry in B)
V_THREE:
        CALL V_HEX
        RC
        SHLD MCOPY
        CALL V_HEX
        RC
        SHLD MCOPY+2
        CALL V_HEX
        SHLD MCOPY+4
        RET

; start..end ok (start <= end)? HL = start, DE = end; carry if not
V_SPAN: LHLD MCOPY+2
        XCHG
        LHLD MCOPY
        MOV A,E
        SUB L
        MOV A,D
        SBB H
        RET

C_F:    CALL V_THREE
        JC V_ERR
        CALL V_SPAN
        JC V_ERR
        MOV A,D
        CPI 0C0h
        JNC V_ROM
        LDA MCOPY+4
        MOV C,A
.l:     MOV M,C
        CALL B_CMP_HL_DE
        INX H
        JNZ .l
        JMP V_OK

C_T:    CALL V_THREE
        JC V_ERR
        CALL BLKMOVE
        JC V_ROM
        JMP V_OK

; C a,b,c: show bytes of a..b that differ from the copy at c
C_C:    CALL V_THREE
        JC V_ERR
        CALL V_SPAN
        JC V_ERR
        PUSH H
        LHLD MCOPY+4
        MOV B,H
        MOV C,L
        POP H
.l:     LDAX B
        CMP M
        JZ .n
        PUSH D
        PUSH B
        PUSH H
        XCHG
        LXI H,LBUF+2
        CALL BHEX16
        MVI M,':'
        INX H
        MVI M,' '
        INX H
        LDAX D
        CALL BHEX8
        MVI M,' '
        INX H
        POP D
        POP B
        PUSH B
        PUSH D
        LDAX B
        CALL BHEX8
        CALL CON_NL
        POP H
        POP B
        POP D
        CALL KSCAN
        INR A
        RNZ
.n:     CALL B_CMP_HL_DE
        INX H
        INX B
        JNZ .l
        JMP V_OK

; S a,b,bytes: addresses where the bytes are; N a,b,byte: addresses whose byte differs
C_S:    CALL V_TWO
        JC V_ERR
        SHLD MCOPY
        XCHG
        SHLD MCOPY+2
        LXI H,MPAT              ; up to 8 bytes
        MVI C,0
.p:     PUSH H
        CALL V_HEX
        MOV A,L
        POP H
        JC .pe
        MOV M,A
        INX H
        INR C
        MOV A,C
        CPI 8
        JC .p
.pe:    MOV A,C
        ORA A
        JZ V_ERR
        STA MPATN
        CALL V_SPAN
        JC V_ERR
.l:     PUSH H
        PUSH D
        LXI D,MPAT
        LDA MPATN
        MOV B,A
.c:     LDAX D
        CMP M
        JNZ .ne
        INX D
        INX H
        DCR B
        JNZ .c
        POP D
        POP H
        CALL V_ADDR
        RC
        JMP .n
.ne:    POP D
        POP H
.n:     CALL B_CMP_HL_DE
        INX H
        JNZ .l
        JMP V_OK

C_N:    CALL V_THREE
        JC V_ERR
        CALL V_SPAN
        JC V_ERR
        LDA MCOPY+4
        MOV C,A
.l:     MOV A,M
        CMP C
        JZ .n
        CALL V_ADDR
        RC
.n:     CALL B_CMP_HL_DE
        INX H
        JNZ .l
        JMP V_OK

; console line "AAAA: hh" for address HL (keeps BC, DE, HL); carry if a key stops it
V_ADDR: PUSH B
        PUSH D
        PUSH H
        XCHG
        LXI H,LBUF+2
        CALL BHEX16
        MVI M,':'
        INX H
        MVI M,' '
        INX H
        LDAX D
        CALL BHEX8
        CALL CON_NL
        POP H
        POP D
        POP B
        PUSH H
        CALL KSCAN
        POP H
        CPI 0FFh
        RZ
        STC
        RET

C_K:    CALL V_TWO
        JC V_ERR
        CALL M_CHECKSUM
        LXI H,LBUF+2
        PUSH B
        LXI D,S_V_SUM
        CALL BSTR
        POP D
        CALL BHEX16
        JMP CON_NL
S_V_SUM: TEXT "сумма ",0

; H a,b: a+b and a-b
C_H:    CALL V_TWO
        JC V_ERR
        PUSH H
        PUSH D
        DAD D
        XCHG
        LXI H,LBUF+2
        CALL BHEX16
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        POP D
        XTHL
        CALL SUB16
        XCHG
        POP H
        CALL BHEX16
        JMP CON_NL

; ---------------------------------------------------------------- G, X, Z, B
; G [addr]: with an address: PC = addr, SP with a return into the debugger; then run
C_G:    CALL V_HEX
        JC .run
        SHLD R_PC
        CALL DBG_SETSP
.run:   JMP DBG_RUN

; X: show the registers in the console; X name value: set one (AF BC DE HL SP PC)
C_X:    CALL V_SKIP
        ORA A
        JZ .show
        CALL UPCASE
        MOV B,A
        INX D
        LDAX D
        CALL UPCASE
        MOV C,A
        INX D
        LXI H,S_V_RN
        MVI A,0
.f:     PUSH PSW
        MOV A,M
        CMP B
        JNZ .nf
        INX H
        MOV A,M
        DCX H
        CMP C
        JZ .found
.nf:    POP PSW
        INX H
        INX H
        INR A
        CPI 6
        JC .f
        JMP V_ERR
.found: POP PSW
        PUSH PSW
        CALL V_HEX
        POP B                   ; B = register number
        JC V_ERR
        XCHG
        MOV A,B
        ADD A
        MOV C,A
        MVI B,0
        LXI H,R_AF
        DAD B
        MOV M,E
        INX H
        MOV M,D
        JMP DEV_REGS
.show:  LXI H,LBUF+2
        LXI D,R_AF
        MVI B,6
.s:     CALL BHEXW
        MVI M,' '
        INX H
        DCR B
        JNZ .s
        JMP CON_NL

; Z [n]: n steps (1 if none), each command shown before it runs
C_Z:    CALL V_HEX
        JNC .n
        LXI H,1
.n:     XRA A
; HL steps, A = 1 to step over CALLs
DEV_STEPS:
        STA ZOVER
        MOV A,H
        ORA L
        RZ
        SHLD ZCOUNT
DEV_STEP1:
        LXI H,LBUF+2            ; trace line: the command about to run
        PUSH H
        LHLD R_PC
        XCHG
        POP H
        CALL CON_DIS
        CALL CON_NL
        LDA ZOVER
        JMP DBG_STEP

; B: list breakpoints; B addr: put one there or take it away
C_B:    CALL V_HEX
        JC .list
        XCHG
        CALL DBG_TOGGLE
        LXI D,S_G_FULL
        JC CON_SAY
.list:  LXI H,LBUF+2
        LXI D,S_V_BP
        CALL BSTR
        LXI D,DBP
        MVI B,4
.p:     LDAX D
        MOV C,A
        INX D
        LDAX D
        DCX D
        ANA C
        INR A
        JZ .e
        CALL BHEXW
        MVI M,' '
        INX H
        JMP .q
.e:     INX D
        INX D
.q:     DCR B
        JNZ .p
        JMP CON_NL
S_V_BP: TEXT "точки: ",0

; the engine stopped the program (a step, a breakpoint, its last RET)
DBG_STOP:
        CALL DEV_REGS
        LDA DEND
        ORA A
        JZ .s
        XRA A
        STA DEND
        STA ZCOUNT
        STA ZCOUNT+1
        LXI D,S_G_END
        CALL CON_SAY
        JMP DEV_LOOP
.s:     LHLD ZCOUNT             ; more steps to do (a key stops them)
        DCX H
        SHLD ZCOUNT
        MOV A,H
        ORA L
        JZ DEV_LOOP
        CALL KSCAN
        CPI 0FFh
        JZ DEV_STEP1
        LXI H,0
        SHLD ZCOUNT
        JMP DEV_LOOP
S_G_END: TEXT "программа вернулась в отладчик (RET)",0

; ---------------------------------------------------------------- tape
C_R:    LXI D,S_T_PLAY
        CALL CON_SAY
        CALL TAPE_RD
        LXI H,LBUF+2
        LHLD TSTART
        XCHG
        LXI H,LBUF+2
        CALL BHEX16
        MVI M,'-'
        INX H
        PUSH H
        LHLD TEND
        XCHG
        POP H
        CALL BHEX16
        MVI M,' '
        INX H
        LDA TSTAT
        LXI D,S_T_OK
        DCR A
        JZ .s
        LXI D,S_T_BAD
        DCR A
        JZ .s
        LXI D,S_T_CUT
.s:     CALL BSTR
        LDA TNAME
        ORA A
        JZ .nn
        MVI M,' '
        INX H
        LXI D,TNAME
        CALL BSTR
.nn:    JMP CON_NL
S_T_PLAY: TEXT "включите воспроизведение (стоп - клавиша, затем ВК)",0
S_T_OK:   TEXT "сумма верна",0
S_T_BAD:  TEXT "СУММА НЕВЕРНА",0
S_T_CUT:  TEXT "обрезано (выше 8DFF)",0

; W a,b: write without a name; O a,b,name: with a name (as Monitor-2's O)
C_W:    XRA A
        STA INBUF
        JMP V_WR
C_O:    CALL V_TWO
        JC V_ERR
        CALL V_SKIP
        LXI H,INBUF             ; the name: capitals, up to 15
        MVI B,15
.n:     LDAX D
        ORA A
        JZ .ne
        CALL V_KOI
        MOV M,A
        INX H
        INX D
        DCR B
        JNZ .n
.ne:    MVI M,0
        LXI D,CMDBUF            ; parse the addresses again
        INX D
        LXI H,INBUF
        JMP V_WR2
V_WR:   LXI H,INBUF
V_WR2:  CALL V_TWO
        JC V_ERR
        SHLD TWS
        XCHG
        SHLD TWE
        CALL V_SPAN2
        JC V_ERR
        LXI D,S_T_REC
        CALL CON_SAY
        CALL GETKEY
        CPI 0Dh
        RNZ
        CALL TAPE_WR
        JMP V_OK
S_T_REC: TEXT "включите запись и нажмите ВК",0
V_SPAN2:
        LHLD TWE
        XCHG
        LHLD TWS
        MOV A,E
        SUB L
        MOV A,D
        SBB H
        RET

; character as Monitor-2 keeps names: Latin small -> capital, Russian small -> capital
V_KOI:  CPI 80h
        RC
        SUI 20h
        CPI 80h
        RC
        SUI 40h
        RET
