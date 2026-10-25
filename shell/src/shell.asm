; =====================================================================================
;  Shell: start-up, screen frame (menu line, status line), program switching
; =====================================================================================

NAPPS   EQU 5

SHELL_INIT:
        LXI SP,STACK_TOP
        ; the variables the base ROM routines rely on, as the base start-up sets them
        LXI H,B_INITVARS
        LXI D,B_INITVARS_END
        LXI B,8FDFh
        CALL B_COPY
        LXI H,0C800h            ; warm start = shell
        SHLD V_WARM
        LXI H,7EFFh
        SHLD V_MEMTOP
        XRA A
        STA INV
        MVI A,0FFh
        STA LASTK
        ; warm start (C800 again: a game's exit, a tape read stopped by a key, F5 program's
        ; RET): keep the running programs and their state if the variables look intact
        LHLD SIG
        MOV A,H
        CPI 0A5h
        JNZ .cold
        MOV A,L
        CPI 5Ah
        JNZ .cold
        LDA CURAPP
        CPI NAPPS
        JC MAIN
.cold:  XRA A
        STA RUS
        STA CURAPP
        MVI A,1
        STA RUNMASK
        LXI H,0A55Ah
        SHLD SIG
        CALL APPS_INIT

MAIN:   LXI SP,STACK_TOP
        CALL REDRAW
.loop:  CALL GETKEY
        CPI 0C1h                ; F1
        JZ PROGMENU
        CPI 09h                 ; ТАБ
        JZ NEXTAPP
        CPI 1Bh                 ; АР2
        JZ CLOSEAPP
        CALL APP_KEY
        JMP .loop

; whole screen: bars and the current program
REDRAW: CALL CLS
        CALL TOPBAR
        CALL STATUSBAR
        JMP APP_DRAW

TOPBAR: MVI C,0
        CALL BARROW
        MVI B,1
        CALL GOTOXY
        CALL INVON
        CALL PRINT
        TEXT "F1 Программы   ТАБ переключить   АР2 закрыть",0
        CALL INVOFF
        ; fall through
SHOW_LANG:
        PUSH H
        PUSH B
        MVI B,60
        MVI C,0
        CALL GOTOXY
        CALL INVON
        LXI H,S_LAT
        LDA RUS
        ORA A
        JZ .l
        LXI H,S_RUS
.l:     CALL PUTS
        CALL INVOFF
        POP B
        POP H
        RET
S_LAT:  TEXT "лат",0
S_RUS:  TEXT "рус",0

STATUSBAR:
        MVI C,31
        CALL BARROW
        MVI B,1
        CALL GOTOXY
        MVI C,0                 ; program number
.p:     LDA RUNMASK
        MOV B,A
        MOV A,C
        INR A
.sh:    DCR A
        JZ .bit
        MOV D,A
        MOV A,B
        RRC
        MOV B,A
        MOV A,D
        JMP .sh
.bit:   MOV A,B
        RRC
        JNC .next
        LDA CURAPP
        CMP C
        JZ .cur
        CALL INVON
        MVI A,' '
        CALL PUTCH
        CALL .name
        MVI A,' '
        CALL PUTCH
        JMP .gap
.cur:   CALL INVOFF
        MVI A,'['
        CALL PUTCH
        CALL .name
        MVI A,']'
        CALL PUTCH
.gap:   CALL INVON
        MVI A,' '
        CALL PUTCH
.next:  INR C
        MOV A,C
        CPI NAPPS
        JNZ .p
        CALL INVOFF
        RET
.name:  PUSH B
        MOV A,C
        CALL APP_NAME
        CALL PUTS
        POP B
        RET

; ---------------------------------------------------------------- switching
NEXTAPP:
        LDA CURAPP
        MOV C,A
        MVI B,NAPPS
.n:     INR C
        MOV A,C
        CPI NAPPS
        JC .ok
        MVI C,0
.ok:    CALL ISRUN
        JNZ .found
        DCR B
        JNZ .n
        JMP MAIN.loop
.found: MOV A,C
        STA CURAPP
        JMP MAIN

; Z clear (NZ) if program C is running
ISRUN:  PUSH B
        LDA RUNMASK
        INR C
.r:     DCR C
        JZ .t
        RRC
        JMP .r
.t:     POP B
        ANI 1
        RET

; bit mask of program C in A
APPBIT: PUSH B
        MVI A,1
        INR C
.r:     DCR C
        JZ .x
        RLC
        JMP .r
.x:     POP B
        RET

CLOSEAPP:
        LDA CURAPP
        ORA A
        JZ MAIN.loop            ; the overview stays
        MOV C,A
        CALL APPBIT
        CMA
        MOV B,A
        LDA RUNMASK
        ANA B
        STA RUNMASK
        JMP NEXTAPP

; ---------------------------------------------------------------- programs menu (F1)
MENU_SEL EQU TMP+2

PROGMENU:
        LDA CURAPP
        STA MENU_SEL
.draw:  MVI B,1
        MVI C,1
        MVI D,26
        MVI E,NAPPS+2
        XRA A
        CALL FRAME
        MVI C,0
.it:    PUSH B
        MVI B,2
        MOV A,C
        ADI 2
        MOV C,A
        CALL GOTOXY
        POP B
        LDA MENU_SEL
        CMP C
        JNZ .ni
        CALL INVON
.ni:    MVI A,' '
        CALL PUTCH
        MOV A,C
        ADI '1'
        CALL PUTCH
        MVI A,' '
        CALL PUTCH
        PUSH B
        MOV A,C
        CALL APP_NAME
        CALL PUTS
        POP B
        CALL ISRUN
        MVI A,' '
        JZ .nr
        MVI A,'✓'
.nr:    CALL PUTCH
        ; pad to the frame
.pad:   LDA CURX
        CPI 26
        JNC .pe
        MVI A,' '
        CALL PUTCH
        JMP .pad
.pe:    CALL INVOFF
        INR C
        MOV A,C
        CPI NAPPS
        JNZ .it
.key:   CALL GETKEY
        CPI 19h                 ; ↑
        JZ .up
        CPI 1Ah                 ; ↓
        JZ .dn
        CPI 0Dh
        JZ .go
        CPI 1Bh
        JZ MAIN
        CPI 0C1h
        JZ MAIN
        SUI '1'
        JC .key
        CPI NAPPS
        JNC .key
        STA MENU_SEL
        JMP .go
.up:    LDA MENU_SEL
        ORA A
        JZ .key
        DCR A
        STA MENU_SEL
        JMP .draw
.dn:    LDA MENU_SEL
        CPI NAPPS-1
        JNC .key
        INR A
        STA MENU_SEL
        JMP .draw
.go:    LDA MENU_SEL
        STA CURAPP
        MOV C,A
        CALL APPBIT
        MOV B,A
        LDA RUNMASK
        ORA B
        STA RUNMASK
        JMP MAIN
