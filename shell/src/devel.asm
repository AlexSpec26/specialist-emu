; =====================================================================================
;  «Специалист» — прошивка «среда разработки» (C000-EFFF): монитор с командами Монитора-2,
;  дизассемблер, однострочный ассемблер, отладчик. Общие части — с оболочкой (main.asm).
; =====================================================================================
        INCLUDE "common.asm"

ABUF    EQU 8E60h               ; 48 bytes: assembler / disassembler work area

        INCLUDE "keytab.inc"
        INCLUDE "coltab.inc"

        INCLUDE "compat1.asm"
        INCLUDE "compat2.asm"   ; CD21-CD28: Monitor-2 bytes
        INCLUDE "scroll.asm"
        INCLUDE "compat3.asm"   ; CE1D: fixed routines
        INCLUDE "compat4.asm"   ; CE5C-CF3A fixed, then tape read
        INCLUDE "kernel.asm"
        INCLUDE "dis.asm"

DEV_INIT:
        LXI SP,STACK_TOP
        LXI H,B_INITVARS
        LXI D,B_INITVARS_END
        LXI B,8FDFh
        CALL B_COPY
        LXI H,0C800h
        SHLD V_WARM
        XRA A
        STA INV
        STA RUS
        MVI A,0FFh
        STA LASTK
        CALL CLS
.l:     CALL GETKEY
        JMP .l

SHOW_LANG:
        RET

ROM_END:
        ASSERT ROM_END <= 0EC00h, "ROM overflow: code runs into the glyphs at EC00"
        ORG 0EC00h
GLYPHS:
        INCLUDE "glyphs.inc"            ; 80-BC
        ASSERT $ == 0EDE8h
        INCLUDE "hexin.asm"
        ASSERT $ <= 0EF00h, "code runs into the pseudographics glyphs at EF00"
        ORG 0EF00h
        INCLUDE "glyphs_hi.inc"         ; E0-FF
        ASSERT $ == 0F000h

START   EQU DEV_INIT
