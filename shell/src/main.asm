; =====================================================================================
;  «Специалист» — оболочка в ПЗУ (C000-EFFF): программы Обзор, Память, Магнитофон, Отладка.
;  Вторая прошивка из тех же исходников — devel.asm (среда разработки).
; =====================================================================================
        INCLUDE "common.asm"

; ---------------------------------------------------------------- data (C836-...)
        INCLUDE "keytab.inc"
        INCLUDE "coltab.inc"

        INCLUDE "compat1.asm"
        INCLUDE "overview.asm"
        INCLUDE "memdesc.asm"
        INCLUDE "gap_cce6.asm"
        INCLUDE "compat2.asm"   ; CD21-CD28: Monitor-2 bytes
        INCLUDE "scroll.asm"
        INCLUDE "gap_cdcc.asm"
        INCLUDE "compat3.asm"   ; CE1D: fixed routines
        INCLUDE "gap_ce29.asm"
        INCLUDE "compat4.asm"   ; CE5C-CF3A fixed, then tape read; ends at CF88
        INCLUDE "kernel.asm"
        INCLUDE "shell.asm"
        INCLUDE "apps.asm"

ROM_END:
        ; glyphs 80-FF at the very end, page-aligned: glyph address = page + code (GADR)
        ASSERT ROM_END <= 0EC00h, "ROM overflow: code runs into the glyphs at EC00"
        ORG 0EC00h
GLYPHS:
        INCLUDE "glyphs.inc"            ; 80-BC
        ; codes BD-DF have no glyph: 280 bytes of code live there (GADR still finds 80-BC
        ; and E0-FF at code*8)
        ASSERT $ == 0EDE8h
        INCLUDE "hexin.asm"
        INCLUDE "gap_eea1.asm"
        ASSERT $ <= 0EF00h, "code runs into the pseudographics glyphs at EF00"
        ORG 0EF00h
        INCLUDE "glyphs_hi.inc"         ; E0-FF
        ASSERT $ == 0F000h

START   EQU SHELL_INIT          ; C800 jumps here (common.asm)
