; =====================================================================================
;  «Обзор»: placed in the free space before CD21 (see compat.asm), the rest of which keeps
;  Monitor-2's bytes for games that read them.
; =====================================================================================

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
        TEXT "F2-F6   команды программы - подсказка внизу её окна",0
        MVI B,4
        MVI C,15
        CALL PRINTAT
        TEXT "Игры работают как с Монитором-2: точки входа C800–C836.",0
        MVI B,3
        MVI C,18
        CALL PRINTAT
        TEXT "Шрифт:",0
        ; character table 20-FF, 32 per row, printed as strings (fast path)
        MVI C,19
        MVI D,20h
.row:   MVI B,4
        CALL GOTOXY
        LXI H,LBUF
.ch:    MOV M,D
        INX H
        INR D
        MOV A,D
        ANI 1Fh
        JNZ .ch
        MVI M,0
        LXI H,LBUF
        CALL PUTS
        INR C
        MOV A,D
        ORA A
        JNZ .row
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

