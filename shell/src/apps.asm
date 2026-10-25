; =====================================================================================
;  Programs. Each has a name, a draw routine (whole work area, rows 1..30) and a key
;  handler (A = key). Stage 1: overview; the others are placeholders.
; =====================================================================================

APPTAB: DW N_OVERVIEW, OVERVIEW_DRAW, OVERVIEW_KEY
        DW N_MEMORY,   STUB_DRAW,     NOKEY
        DW N_DEBUG,    STUB_DRAW,     NOKEY
        DW N_TAPE,     STUB_DRAW,     NOKEY
        DW N_MONITOR,  STUB_DRAW,     NOKEY
        DW N_EDITOR,   STUB_DRAW,     NOKEY

N_OVERVIEW: TEXT "Обзор",0
N_MEMORY:   TEXT "Память",0
N_DEBUG:    TEXT "Отладка",0
N_TAPE:     TEXT "Магнитофон",0
N_MONITOR:  TEXT "Монитор",0
N_EDITOR:   TEXT "Редактор",0

APPS_INIT:
        RET

; HL = entry of program A in APPTAB
APP_ENTRY:
        MOV L,A
        ADD A
        ADD L
        ADD A                   ; *6
        MOV L,A
        MVI H,0
        LXI D,APPTAB
        DAD D
        RET

; HL = name of program A
APP_NAME:
        PUSH D
        CALL APP_ENTRY
        MOV E,M
        INX H
        MOV D,M
        XCHG
        POP D
        RET

; jump to the word at HL + A
APP_CALL:
        PUSH H
        LDA CURAPP
        CALL APP_ENTRY
        POP D
        DAD D
        MOV E,M
        INX H
        MOV D,M
        XCHG
        PCHL

APP_DRAW:
        LXI H,2
        JMP APP_CALL

APP_KEY:
        STA TMP
        LXI H,4
        JMP APP_CALL

NOKEY:  RET

; ---------------------------------------------------------------- overview
OVERVIEW_DRAW:
        MVI B,0
        MVI C,1
        MVI D,63
        MVI E,30
        MVI A,1
        CALL FRAME
        MVI B,2
        MVI C,1
        CALL PRINTAT
        TEXT " Обзор ",0
        MVI B,3
        MVI C,3
        CALL PRINTAT
        TEXT "Специалист — оболочка, этап 1: ядро и совместимость",0
        MVI B,3
        MVI C,5
        CALL PRINTAT
        TEXT "КР580ВМ80А, 2 МГц.  ОЗУ 0000–8FFF, экран 9000–BFFF.",0
        MVI B,3
        MVI C,6
        CALL PRINTAT
        TEXT "ПЗУ: C000–C7FF — базовое, C800–EFFF — оболочка.",0
        MVI B,2
        MVI C,8
        MVI D,61
        MVI E,16
        XRA A
        CALL FRAME
        MVI B,4
        MVI C,8
        CALL PRINTAT
        TEXT " Клавиши ",0
        MVI B,4
        MVI C,9
        CALL PRINTAT
        TEXT "F1      список программ (стрелки и ВК или цифра)",0
        MVI B,4
        MVI C,10
        CALL PRINTAT
        TEXT "ТАБ     следующая запущенная программа",0
        MVI B,4
        MVI C,11
        CALL PRINTAT
        TEXT "АР2     закрыть программу",0
        MVI B,4
        MVI C,12
        CALL PRINTAT
        TEXT "Р/Л     русские / латинские буквы,  НР — заглавные",0
        MVI B,4
        MVI C,13
        CALL PRINTAT
        TEXT "НР+F11  (позже) заморозить игру и открыть отладку",0
        MVI B,4
        MVI C,15
        CALL PRINTAT
        TEXT "Игры работают как с Монитором-2: точки входа C800–C836.",0
        MVI B,3
        MVI C,18
        CALL PRINTAT
        TEXT "Шрифт:",0
        ; character table 20-7F and 80-FF
        MVI C,19
        MVI D,20h
.row:   MVI B,4
        CALL GOTOXY
.ch:    MOV A,D
        CALL PUTCH
        INR D
        JZ .end
        MOV A,D
        ANI 1Fh
        JNZ .ch
        INR C
        JMP .row
.end:   MVI B,3
        MVI C,26
        CALL PRINTAT
        TEXT "Съешь же ещё этих мягких французских булок, да выпей чаю.",0
        MVI B,3
        MVI C,27
        CALL PRINTAT
        TEXT "The quick brown fox jumps over the lazy dog. 0123456789",0
        MVI B,3
        MVI C,28
        CALL PRINTAT
        TEXT "Последняя клавиша: ",0
        RET

OVERVIEW_KEY:
        MVI B,22
        MVI C,28
        CALL GOTOXY
        LDA TMP
        CALL PHEX8
        MVI A,' '
        CALL PUTCH
        LDA TMP
        CPI 20h
        RC
        JMP PUTCH

; ---------------------------------------------------------------- placeholder
STUB_DRAW:
        MVI B,0
        MVI C,1
        MVI D,63
        MVI E,30
        MVI A,1
        CALL FRAME
        MVI B,2
        MVI C,1
        CALL GOTOXY
        MVI A,' '
        CALL PUTCH
        LDA CURAPP
        CALL APP_NAME
        CALL PUTS
        MVI A,' '
        CALL PUTCH
        MVI B,4
        MVI C,14
        CALL PRINTAT
        TEXT "Программа появится на следующем этапе.",0
        MVI B,4
        MVI C,16
        CALL PRINTAT
        TEXT "ТАБ — к другой программе,  АР2 — закрыть,  F1 — список.",0
        RET
