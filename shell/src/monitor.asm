; =====================================================================================
;  «Монитор» (ROM-007) — убран владельцем 2026-10-25 ради отладчика («попробуй для начала
;  выкинуть монитор и вернуть отладчик»). НЕ подключён. Работал в rom-build-8/9
;  (tools/ShellMonTest.java). Переменные APPVARS+26h.. и CMDBUF 8E50 теперь заняты
;  другими — при возврате переразметить.
; =====================================================================================

; ---------------------------------------------------------------- monitor (ROM-007)
; Command line. Output goes to rows 2..25 top-down and wraps to the top; the row after the
; newest line is kept empty. Commands (Latin letters, numbers in hex):
;   D [адр [кон]]   dump      M адр бб бб ...   write bytes      F адр кон бб   fill
;   T адр кон куда  copy      G адр             run (its RET comes back to the shell)
ORow    EQU APPVARS+26h         ; next output row
CMDLEN  EQU APPVARS+27h         ; characters typed
DLAST   EQU APPVARS+28h         ; 2: next address for D
MDEND   EQU APPVARS+2Ah         ; 2: end for D
CMDBUF  EQU 8E50h               ; 48 bytes: the command being typed
CMDMAX  EQU 47
MOROW0  EQU 2
MOROWN  EQU 26                  ; one past the last output row
MPROMPT EQU 28

MON_DRAW:
        CALL PRINTL
        DB 4,26
        TEXT "D адр кон  M адр бб..  F адр кон бб  T адр кон куда  G адр",0
        DB 0FFh
        MVI A,MOROW0
        STA ORow
; the input line: "> command_"
MON_PROMPT:
        LXI H,LBUF
        MVI M,'>'
        INX H
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
        MVI C,58
        CALL BPADTO
        MVI B,4
        MVI C,MPROMPT
        JMP BPRINT

MON_KEY:
        LDA TMP
        CPI 0Dh
        JZ MON_EXEC
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
        MOV A,E                 ; echo: character and cursor
        ADI 6
        MOV B,A
        MVI C,MPROMPT
        CALL GOTOXY
        MOV A,M
        CALL PUTCH
        MVI A,'_'
        JMP PUTCH
.bs:    LDA CMDLEN
        ORA A
        RZ
        DCR A
        STA CMDLEN
        ADI 6
        MOV B,A
        MVI C,MPROMPT
        CALL GOTOXY
        MVI A,'_'
        CALL PUTCH
        MVI A,' '
        JMP PUTCH

; one output line: LBUF+2 .. HL is the text; borders added; the next row is cleared
MON_NL: MVI C,63
        CALL BPADTO
        MVI M,C_VL2
        INX H
        MVI M,0
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        MVI M,' '
        LDA ORow
        MOV C,A
        MVI B,0
        CALL GOTOXY
        LXI H,LBUF
        CALL PUTS
        LDA ORow
        INR A
        CPI MOROWN
        JC .ok
        MVI A,MOROW0
.ok:    STA ORow
        MOV C,A
        MVI B,1
        CALL GOTOXY
        MVI A,' '
        MVI B,62
        JMP REPCH

; string DE as an output line
MON_SAY:
        LXI H,LBUF+2
        CALL BSTR
        JMP MON_NL

; ВК: show the command, run it
MON_EXEC:
        LXI H,LBUF+2
        MVI M,'>'
        INX H
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
.e:     XRA A
        STAX D                  ; command ends with 00
        STA CMDLEN
        CALL MON_NL
        CALL MON_PROMPT
        LXI D,CMDBUF
        CALL MON_SKIP
        ORA A
        RZ
        INX D
        CPI 0A1h                ; Latin lower case -> capital
        JC .u
        SUI 0A1h-41h
.u:     CPI 'D'
        JZ MON_D
        CPI 'M'
        JZ MON_M
        CPI 'F'
        JZ MON_F
        CPI 'T'
        JZ MON_T
        CPI 'G'
        JZ MON_G
MON_ERR:
        LXI D,S_MON_ERR
        JMP MON_SAY
S_MON_ERR: TEXT "? команды: D M F T G, числа в hex",0
S_MON_OK:  TEXT "готово",0
S_MON_ROM: TEXT "? только ОЗУ 0000-BFFF",0

; DE -> first non-space character, in A
MON_SKIP:
        LDAX D
        CPI ' '
        RNZ
        INX D
        JMP MON_SKIP

; hex number at DE -> HL; carry if there is none
MON_HEX:
        CALL MON_SKIP
        LXI H,0
        MVI B,0
.l:     LDAX D
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

; D [start [end]]: 8 bytes a line; a key stops it
MON_D:  CALL MON_HEX
        JC .cont
        SHLD DLAST
        CALL MON_HEX
        JNC .end
.cont:  LHLD DLAST
        LXI D,127
        DAD D
.end:   SHLD MDEND
MON_DUMP:
.l:     LHLD DLAST
        XCHG
        LXI H,LBUF+2
        CALL BHEX16
        MVI M,':'
        INX H
        MVI M,' '
        INX H
        MVI B,8
.b:     LDAX D
        CALL BHEX8
        MVI M,' '
        INX H
        INX D
        DCR B
        JNZ .b
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
        CALL MEM_CH
        MOV M,A
        INX H
        INX D
        DCR B
        JNZ .t
        PUSH D
        CALL MON_NL
        POP D
        XCHG
        SHLD DLAST
        XCHG
        LHLD MDEND
        CALL SUB16              ; end - next < 0: done
        RC
        MOV A,D                 ; wrapped past FFFF: done
        ORA E
        RZ
        CALL KSCAN
        INR A
        RNZ                     ; a key stops it
        JMP .l

; M addr bytes...: write, then show the line
MON_M:  CALL MON_HEX
        JC MON_ERR
        SHLD DLAST
        SHLD MDEND
.l:     PUSH H
        CALL MON_HEX
        MOV A,L
        POP H
        JC MON_DUMP
        MOV C,A
        MOV A,H
        CPI 0C0h
        JNC MON_ROM
        MOV M,C
        INX H
        JMP .l
MON_ROM:
        LXI D,S_MON_ROM
        JMP MON_SAY

; F start end byte
MON_F:  CALL MON_HEX
        JC MON_ERR
        PUSH H
        CALL MON_HEX
        PUSH H
        CALL MON_HEX
        MOV C,L
        POP D                   ; end
        POP H                   ; start
        JC MON_ERR
        MOV A,E
        SUB L
        MOV A,D
        SBB H
        JC MON_ERR              ; end before start
        MOV A,D
        CPI 0C0h
        JNC MON_ROM
.l:     MOV M,C
        CALL B_CMP_HL_DE
        INX H
        JNZ .l
        JMP MON_OKMSG

; T start end dest
MON_T:  CALL MON_HEX
        JC MON_ERR
        SHLD MCOPY
        CALL MON_HEX
        JC MON_ERR
        SHLD MCOPY+2
        CALL MON_HEX
        JC MON_ERR
        SHLD MCOPY+4
        CALL BLKMOVE
        JC MON_ROM
MON_OKMSG:
        LXI D,S_MON_OK
        JMP MON_SAY

; G addr: run; its RET comes back to the shell
MON_G:  CALL MON_HEX
        JC MON_ERR
        LXI SP,STACK_TOP
        LXI D,0C800h
        PUSH D
        PCHL
