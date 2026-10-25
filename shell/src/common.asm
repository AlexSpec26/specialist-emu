; =====================================================================================
;  «Специалист»: common part of both ROMs (shell and devel): constants, RAM, base ROM,
;  Monitor-2 entry table. START is defined by the top file.
;
;  C000-C7FF  базовое ПЗУ «Специалиста» (подпрограммы клавиатуры, экрана, ленты и шрифт)
;             без единого изменения: около 60 % игр вызывают эти подпрограммы напрямую,
;             читают шрифт C400-C7FF, а некоторые — даже байты кода как данные.
;  C800-C838  таблица точек входа монитора, совместимая с Монитором-2.
;  C836-EBFF  оболочка;  EC00-EFFF  доп. символы 80-FF (выровнены по странице).
; =====================================================================================

FONT            EQU 0C400h          ; base font, codes 00-7F, 8 bytes each (bits 4..0)
SCREEN          EQU 9000h
PPI_A           EQU 0FF00h
PPI_B           EQU 0FF01h
PPI_C           EQU 0FF02h
PPI_CTL         EQU 0FF03h

; base ROM routines and variables (addresses fixed by the base ROM)
B_CLS           EQU 0C010h          ; clear screen with the word at (8FFA)
B_PUTC          EQU 0C037h          ; print character C (monitor text grid)
B_GETKEY        EQU 0C337h          ; wait for a key, code in A
B_TAPE_IN       EQU 0C377h          ; read a byte from tape
B_TAPE_OUT      EQU 0C3D0h          ; write byte C to tape
B_CMP_HL_DE     EQU 0C427h          ; Z if HL = DE
B_COPY          EQU 0C42Dh          ; copy HL..DE to (BC)
B_INITVARS      EQU 0C473h          ; initial values of 8FDF..8FFF
B_INITVARS_END  EQU 0C494h
V_WARM          EQU 8FE1h           ; warm start vector
V_FILL          EQU 8FFAh           ; screen fill word for B_CLS
V_CURSOR        EQU 8FFCh           ; monitor cursor: 8FFC = Y, 8FFD = X
V_MEMTOP        EQU 8FABh           ; top of user memory (Monitor-2)

; ---------------------------------------------------------------- shell RAM (8E80-8F7F)
STACK_TOP       EQU 8F00h
VARS            EQU 8F00h
INV             EQU VARS+0          ; 0 or 3Fh: inverse text
CURX            EQU VARS+1          ; text column 0..63
CURY            EQU VARS+2          ; text row 0..31
LASTK           EQU VARS+3          ; last scanned key code, FF = none
RUS             EQU VARS+4          ; 0 = Latin, 1 = Russian letters
REPCNT          EQU VARS+5          ; 2 bytes: autorepeat countdown
CURAPP          EQU VARS+7          ; current program
RUNMASK         EQU VARS+8          ; bit n = program n is running
SHIFTK          EQU VARS+9          ; НР held during the last key
SIG             EQU VARS+0Ah        ; 2 bytes: A55A = shell variables are set (warm start)
GBUF            EQU VARS+10h        ; 8 bytes: glyph being drawn
TMP             EQU VARS+18h        ; scratch
GP1             EQU VARS+20h        ; 2 bytes each: glyph addresses for GRP4
GP2             EQU VARS+22h
GP3             EQU VARS+24h
SPTR            EQU VARS+26h        ; 2 bytes: string pointer after GRP4
CH4             EQU VARS+28h        ; 4 bytes: repeated character for REPCH
SSAVE           EQU VARS+2Ch        ; 2 bytes: SP saved while scrolling
SCREND          EQU VARS+2Eh        ; scrolling stops at this screen page
APPVARS         EQU VARS+30h        ; 50h bytes: state of the programs (kept while switching)
LBUF            EQU 8E00h           ; 64 bytes: line being built (page-aligned: L = length)
INBUF           EQU 8E40h           ; 16 bytes: digits typed by HEXIN
; 8E50-8E7F: command line of «Монитор»; shell stack: 8F00 down to about 8E80

; characters of the shell set
C_HL            EQU 0E0h            ; ─
C_VL2           EQU 0ECh            ; ║
C_TL            EQU 0F1h            ; ╟
C_TR            EQU 0F2h            ; ╢
C_FULL          EQU 0F3h            ; █
C_DOT           EQU 0FAh            ; ·

; ---------------------------------------------------------------- base ROM
        ORG 0C000h
        ; Kept byte for byte: games call its routines and even read its bytes as data.
        ; After reset it shows "PROGRAM ?": ВК starts the shell (C800), F1-F11 + tape
        ; loads a program, as on the original machine.
        INCBIN "../base/spec_m2.rom", 0, 800h
        ASSERT $ == 0C800h

; ---------------------------------------------------------------- monitor entry points
        JMP START           ; C800 restart
        JMP B_GETKEY        ; C803 wait for a key
        JMP B_TAPE_IN       ; C806 read a byte from tape
        JMP M_PUTC          ; C809 print character C
        JMP B_TAPE_OUT      ; C80C write byte C to tape
        JMP M_RET           ; C80F (line input: not used by programs)
        JMP M_KEYSTAT       ; C812 key status, no wait
        JMP M_PUTHEX        ; C815 print A in hex
        JMP M_PUTS          ; C818 print string (HL), ends with 00
        JMP M_KEYSTAT       ; C81B key code without waiting
        JMP M_GETCUR        ; C81E cursor position in HL
M_RET:  RET                 ; C821
        NOP
        NOP
        JMP M_TAPE_RD       ; C824 read block from tape
        JMP M_RET           ; C827 write block to tape (later)
        JMP M_CHECKSUM      ; C82A checksum HL..DE -> BC
        RET                 ; C82D
        NOP
        NOP
        JMP M_SETTOP        ; C830 set top of memory (HL)
        JMP M_GETTOP        ; C833 get top of memory
        ASSERT $ == 0C836h

