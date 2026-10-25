; =====================================================================================
;  Programs. Each has a name, a draw routine (whole work area, rows 1..30) and a key
;  handler (A = key). Stage 1: overview; the others are placeholders.
; =====================================================================================

APPTAB: DW N_OVERVIEW, OVERVIEW_DRAW, OVERVIEW_KEY
        DW N_MEMORY,   MEM_DRAW,      MEM_KEY
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
        LXI H,0
        SHLD MCUR
        SHLD MTOP
        XRA A
        STA MEDIT
        STA MPATN
        DCR A
        STA MNIB
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

; ---------------------------------------------------------------- memory (ROM-002)
; Rows 4..19: 16 lines of 8 bytes (address, hex, text). Right: the 8 bytes of the cursor's
; line as a picture (a Specialist screen column is 8 such bytes stacked). Row 21: the byte in
; hex, binary, decimal; row 22: what this address is. Row 27: prompts and messages.
MCUR    EQU APPVARS+0           ; 2: cursor address
MTOP    EQU APPVARS+2           ; 2: address of the first line shown
MEDIT   EQU APPVARS+4           ; 1 = editing
MNIB    EQU APPVARS+5           ; first hex digit typed while editing, FF = none
MPATN   EQU APPVARS+6           ; search pattern length (0 = none)
MPAT    EQU APPVARS+7           ; 8 bytes: search pattern
MBROW   EQU APPVARS+0Fh         ; 2: line shown in the picture
MCOPY   EQU APPVARS+11h         ; 6: transfer: from, to/length, destination
MROW0   EQU 4                   ; first data row
MCOLS   EQU 33                  ; screen bytes to scroll (text columns 0..43)
MMSG    EQU 27                  ; message row

MEM_DRAW:
        MVI B,0
        MVI C,1
        MVI D,63
        MVI E,30
        MVI A,1
        CALL FRAME
        MVI B,2
        MVI C,1
        CALL PRINTAT
        TEXT " Память ",0
        MVI B,2
        MVI C,2
        CALL PRINTAT
        TEXT "Адрес +0 +1 +2 +3 +4 +5 +6 +7  Текст      Биты",0
        MVI B,0
        MVI C,20
        CALL GOTOXY
        MVI A,C_TL
        CALL PUTCH
        MVI A,C_HL
        MVI B,62
        CALL REPCH
        MVI A,C_TR
        CALL PUTCH
        MVI B,4
        MVI C,24
        CALL PRINTAT
        TEXT "◄►▲▼ курсор    НР+▲▼ страница    ВК правка (цифры 0-9 A-F)",0
        MVI B,4
        MVI C,25
        CALL PRINTAT
        TEXT "F2 адрес       F3 поиск          F4 перенос блока",0
        CALL MEM_ROWS
        CALL MEM_NOPIC
        JMP MEM_SHOW

; the picture must be drawn again (memory changed)
MEM_NOPIC:
        LDA MCUR
        CMA
        STA MBROW
        RET

; all 16 data lines
MEM_ROWS:
        XRA A
.l:     PUSH PSW
        CALL MEM_ROW
        POP PSW
        INR A
        CPI 16
        JNZ .l
        RET

; data line A (0..15): "║ AAAA  hh hh hh hh hh hh hh hh  tttttttt   "
MEM_ROW:
        PUSH PSW
        MOV L,A
        MVI H,0
        DAD H
        DAD H
        DAD H
        XCHG
        LHLD MTOP
        DAD D
        XCHG                    ; DE = address of the line
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        MVI M,' '
        INX H
        CALL BHEX16
        MVI M,' '
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
        MVI C,44
        CALL BPADTO
        POP PSW
        ADI MROW0
        MOV C,A
        MVI B,0
        JMP BPRINT

; A = byte -> character to show for it (control codes as ·)
MEM_CH: CPI 20h
        RNC
        MVI A,C_DOT
        RET

; cursor on screen: carry if not visible; else C = row, D = byte in line (0..7)
MEM_POS:
        LHLD MTOP
        XCHG
        LHLD MCUR
        MOV A,L
        SUB E
        MOV L,A
        MOV A,H
        SBB D
        JNZ .no
        MOV A,L
        CPI 128
        JNC .no
        MOV E,A
        ANI 7
        MOV D,A
        MOV A,E
        RRC
        RRC
        RRC
        ANI 1Fh
        ADI MROW0
        MOV C,A
        ORA A
        RET
.no:    STC
        RET

; draw the cursor cell: A = 3Fh marked, 0 plain
MEM_MARK:
        STA INV
        CALL MEM_POS
        JC .x
        MOV A,D
        ADD A
        ADD D
        ADI 8
        MOV B,A
        CALL GOTOXY
        LHLD MCUR
        LDA MNIB
        CPI 0FFh
        JZ .full
        CALL HEXDIG             ; first digit typed, waiting for the second
        CALL PUTCH
        MVI A,'_'
        CALL PUTCH
        JMP .txt
.full:  MOV A,M
        CALL PHEX8
.txt:   MOV A,D
        ADI 33
        MOV B,A
        CALL GOTOXY
        MOV A,M
        CALL MEM_CH
        CALL PUTCH
.x:     XRA A
        STA INV
        RET

; cursor, info lines and the picture
MEM_SHOW:
        MVI A,3Fh
        CALL MEM_MARK
        CALL MEM_INFO
        JMP MEM_BITS

; rows 21-22: "AAAA:  hh  bbbbbbbb  ddd" and the description
MEM_INFO:
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        PUSH H
        LHLD MCUR
        XCHG
        POP H
        CALL BHEX16
        MVI M,':'
        INX H
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        LDAX D
        PUSH PSW
        CALL BHEX8
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        POP PSW
        PUSH PSW
        CALL BBIN8
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        POP PSW
        CALL BDEC
        MVI C,48
        CALL BPADTO
        LDA MEDIT
        ORA A
        JZ .ne
        PUSH D
        LXI D,S_EDIT
        CALL BSTR
        POP D
.ne:    MVI C,63
        CALL BPADTO
        MVI B,0
        MVI C,21
        CALL BPRINT
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        PUSH H
        LHLD MCUR
        XCHG
        POP H
        CALL MEM_DESC
        MVI C,63
        CALL BPADTO
        MVI B,0
        MVI C,22
        JMP BPRINT
S_EDIT: TEXT 01h," ПРАВКА ",02h,0

; DE = address -> its description at HL
MEM_DESC:
        MOV A,D
        CPI 8Eh
        JC .user
        JZ .buf
        CPI 8Fh
        JNZ .hi
        MOV A,E
        CPI 80h
        JC .svar
        LXI D,S_D_MVAR
        JMP BSTR
.user:  LXI D,S_D_USER
        JMP BSTR
.buf:   LXI D,S_D_BUF
        JMP BSTR
.svar:  LXI D,S_D_SVAR
        JMP BSTR
.hi:    CPI 0C0h
        JC .scr
        CPI 0C5h
        JC .base
        CPI 0C8h
        JC .font
        CPI 0ECh
        JC .rom
        CPI 0F0h
        JC .glyph
        CPI 0F8h
        JC .none
        MOV A,E                 ; keyboard ВВ55
        PUSH PSW
        LXI D,S_D_PPI
        CALL BSTR
        POP PSW
        ANI 3
        CPI 3
        JZ .ctl
        ADI 'A'
        MOV M,A
        INX H
        RET
.ctl:   LXI D,S_D_CTL
        JMP BSTR
.none:  LXI D,S_D_NONE
        JMP BSTR
.base:  LXI D,S_D_BASE
        JMP BSTR
.rom:   CPI 0C8h
        JNZ .shell
        MOV A,E
        CPI 36h
        JNC .shell
        LXI D,S_D_ENTRY
        JMP BSTR
.shell: LXI D,S_D_SHELL
        JMP BSTR
.scr:   PUSH D
        LXI D,S_D_SCR
        CALL BSTR
        POP D
        MOV A,D
        SUI 90h
        CALL BDEC
        PUSH D
        LXI D,S_D_LINE
        CALL BSTR
        POP D
        MOV A,E
        JMP BDEC
.font:  PUSH D
        LXI D,S_D_FONT
        CALL BSTR
        POP D
        MOV A,D
        SUI 0C4h                ; font = C400 + code*8, codes 20-7F
        JMP .chr
.glyph: PUSH D
        LXI D,S_D_GLYPH
        CALL BSTR
        POP D
        MOV A,D
        SUI 0ECh-4              ; codes 80-FF
.chr:   ; A = page offset (0..7): code = A*32 + E/8
        RRC
        RRC
        RRC
        ANI 0E0h
        MOV B,A
        MOV A,E
        RRC
        RRC
        RRC
        ANI 1Fh
        ORA B
        PUSH PSW
        CALL BHEX8
        MVI M,'h'
        INX H
        MVI M,' '
        INX H
        MVI M,'"'
        INX H
        POP PSW
        CPI 3                   ; 00-02 cannot go into a string
        JNC .c
        MVI A,C_DOT
.c:     MOV M,A
        INX H
        MVI M,'"'
        INX H
        PUSH D
        LXI D,S_D_ROW
        CALL BSTR
        POP D
        MOV A,E
        ANI 7
        INR A
        CALL BDEC
        LXI D,S_D_OF8
        JMP BSTR

S_D_USER:  TEXT "ОЗУ: программы и данные",0
S_D_BUF:   TEXT "ОЗУ: буфер строки и стек оболочки (8E00-8EFF)",0
S_D_SVAR:  TEXT "ОЗУ: переменные оболочки (8F00-8F7F)",0
S_D_MVAR:  TEXT "ОЗУ: переменные монитора (8F80-8FFF)",0
S_D_SCR:   TEXT "экран: байт-столбец ",0
S_D_LINE:  TEXT ", строка точек ",0
S_D_BASE:  TEXT "ПЗУ: базовые подпрограммы (C000-C4FF)",0
S_D_FONT:  TEXT "ПЗУ: шрифт, символ ",0
S_D_GLYPH: TEXT "ПЗУ: доп. символы, символ ",0
S_D_ROW:   TEXT ", строка ",0
S_D_OF8:   TEXT " из 8",0
S_D_ENTRY: TEXT "ПЗУ: точки входа монитора (C800-C835)",0
S_D_SHELL: TEXT "ПЗУ: оболочка",0
S_D_NONE:  TEXT "здесь нет памяти (F000-F7FF)",0
S_D_PPI:   TEXT "клавиатура ВВ55 (F800-FFFF), регистр ",0
S_D_CTL:   TEXT "управления",0

; the picture of the cursor's 8-byte line (only when that line changed)
MEM_BITS:
        LHLD MCUR
        MOV A,L
        ANI 0F8h
        MOV L,A
        XCHG                    ; DE = line
        LHLD MBROW
        MOV A,L
        CMP E
        JNZ .new
        MOV A,H
        CMP D
        RZ
.new:   XCHG
        SHLD MBROW
        XCHG
        LXI H,LBUF
        CALL BHEX16
        MVI M,'-'
        INX H
        MOV A,E
        ORI 7
        MOV E,A
        CALL BHEX16
        MVI C,16
        CALL BPADTO
        MVI B,44
        MVI C,MROW0
        CALL BPRINT
        LHLD MBROW
        XCHG
        MVI C,MROW0+2
.r:     LXI H,LBUF
        LDAX D
        PUSH D
        MOV D,A
        MVI E,8
.bit:   MOV A,D
        RLC
        MOV D,A
        JNC .z
        MVI M,C_FULL
        INX H
        MVI M,C_FULL
        JMP .n
.z:     MVI M,C_DOT
        INX H
        MVI M,' '
.n:     INX H
        DCR E
        JNZ .bit
        POP D
        PUSH B
        MVI B,44
        CALL BPRINT
        POP B
        INX D
        INR C
        MOV A,C
        CPI MROW0+10
        JNZ .r
        RET

; ---------------------------------------------------------------- memory: keys
MEM_KEY:
        LDA MEDIT
        ORA A
        JZ .nav
        LDA TMP
        CALL HEXVAL
        JNC MEM_DIGIT
.nav:   LDA TMP
        LXI D,1
        CPI 18h                 ; →
        JZ MEM_MOVE
        LXI D,-1
        CPI 08h                 ; ←
        JZ MEM_MOVE
        CPI 19h                 ; ↑
        JZ .up
        CPI 1Ah                 ; ↓
        JZ .dn
        CPI 0Dh                 ; ВК
        JZ .edit
        CPI 0C2h                ; F2
        JZ MEM_GO
        CPI 0C3h                ; F3
        JZ MEM_FIND
        CPI 0C4h                ; F4
        JZ MEM_COPY
        RET
.up:    LXI D,-8
        LDA SHIFTK
        ORA A
        JZ MEM_MOVE
        LXI D,-128
        JMP MEM_MOVE
.dn:    LXI D,8
        LDA SHIFTK
        ORA A
        JZ MEM_MOVE
        LXI D,128
        JMP MEM_MOVE
.edit:  LDA MEDIT
        XRI 1
        STA MEDIT
        MVI A,0FFh
        STA MNIB
        CALL MEM_CLRMSG
        JMP MEM_SHOW

; move the cursor by DE; scroll one line if it just leaves the window, else jump
MEM_MOVE:
        MVI A,0FFh
        STA MNIB
        XRA A
        PUSH D
        CALL MEM_MARK
        POP D
        LHLD MCUR
        DAD D
        SHLD MCUR
        CALL MEM_POS
        JNC MEM_SHOW
        LHLD MTOP
        XCHG
        LHLD MCUR
        MOV A,L
        SUB E
        MOV L,A
        MOV A,H
        SBB D
        MOV H,A                 ; HL = cursor - top
        ORA A
        JNZ .above
        MOV A,L
        CPI 136
        JNC MEM_JUMP
        LHLD MTOP               ; just below: scroll up
        LXI D,8
        DAD D
        SHLD MTOP
        MVI B,MROW0
        MVI C,MROW0+15
        MVI E,MCOLS
        CALL SCROLLUP
        MVI A,15
        CALL MEM_ROW
        JMP MEM_SHOW
.above: INR A
        JNZ MEM_JUMP
        MOV A,L
        CPI 0F8h
        JC MEM_JUMP
        LHLD MTOP               ; just above: scroll down
        LXI D,-8
        DAD D
        SHLD MTOP
        MVI B,MROW0
        MVI C,MROW0+15
        MVI E,MCOLS
        CALL SCROLLDN
        XRA A
        CALL MEM_ROW
        JMP MEM_SHOW

; cursor somewhere else: its line goes to the middle of the window
MEM_JUMP:
        LHLD MCUR
        MOV A,L
        ANI 0F8h
        MOV L,A
        LXI D,-64
        DAD D
        SHLD MTOP
        CALL MEM_ROWS
        JMP MEM_SHOW

; editing: A = hex digit
MEM_DIGIT:
        MOV B,A
        LDA MNIB
        CPI 0FFh
        JNZ .second
        MOV A,B
        STA MNIB
        MVI A,3Fh
        JMP MEM_MARK
.second:
        RLC
        RLC
        RLC
        RLC
        ORA B
        MOV C,A
        MVI A,0FFh
        STA MNIB
        LHLD MCUR
        MOV A,H
        CPI 0C0h
        JNC .rom
        MOV M,C
        CALL MEM_POS
        MOV A,C
        SUI MROW0
        CALL MEM_ROW
        CALL MEM_NOPIC
        LXI D,1
        JMP MEM_MOVE
.rom:   LXI H,S_M_ROM
        CALL MEM_MSG
        MVI A,3Fh
        JMP MEM_MARK

; message (HL) on the message row
MEM_MSG:
        PUSH H
        CALL MEM_CLRMSG
        POP H
        JMP PUTS
MEM_CLRMSG:
        MVI B,1
        MVI C,MMSG
        CALL GOTOXY
        MVI A,' '
        MVI B,62
        CALL REPCH
        MVI B,4
        MVI C,MMSG
        JMP GOTOXY

; ask for an address: prompt (HL) on the message row -> HL, carry if cancelled or empty
MEM_ASK:
        CALL MEM_MSG
MEM_ASK2:
        MVI B,4
        CALL HEXIN
        RC
        MOV A,C
        ORA A
        STC
        RZ
        CALL DIG2HL
        ORA A
        RET

; F2: go to an address (it becomes the first line)
MEM_GO: LXI H,S_M_GO
        CALL MEM_ASK
        JC MEM_CLRMSG
        SHLD MCUR
        MOV A,L
        ANI 0F8h
        MOV L,A
        SHLD MTOP
        MVI A,0FFh
        STA MNIB
        CALL MEM_CLRMSG
        CALL MEM_ROWS
        JMP MEM_SHOW

; F3: find bytes after the cursor (whole memory, wrapping); empty input = the same again
MEM_FIND:
        LXI H,S_M_FIND
        CALL MEM_MSG
        MVI B,16
        CALL HEXIN
        JC MEM_CLRMSG
        MOV A,C
        ORA A
        JZ .again
        RRC                     ; pairs of digits -> bytes
        ANI 7Fh
        JZ MEM_CLRMSG
        STA MPATN
        MOV B,A
        LXI D,INBUF
        LXI H,MPAT
.p:     LDAX D
        RLC
        RLC
        RLC
        RLC
        INX D
        MOV C,A
        LDAX D
        ORA C
        INX D
        MOV M,A
        INX H
        DCR B
        JNZ .p
.again: LDA MPATN
        ORA A
        JZ MEM_CLRMSG
        LXI H,S_M_SEARCH
        CALL MEM_MSG
        LDA MPAT
        MOV E,A
        LHLD MCUR
        LXI B,0                 ; 65536 places
.l:     INX H
        MOV A,E
        CMP M
        JZ .cand
.n:     DCX B
        MOV A,B
        ORA C
        JNZ .l
        LXI H,S_M_NOTF
        JMP MEM_MSG
.cand:  PUSH H
        PUSH B
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
        POP B
        POP H
        SHLD MCUR
        LXI H,S_M_FOUND
        CALL MEM_MSG
        MVI A,0FFh
        STA MNIB
        JMP MEM_JUMP
.ne:    POP B
        POP H
        LDA MPAT
        MOV E,A
        JMP .n

; F4: copy a block (overlapping is fine); only into ОЗУ 0000-BFFF
MEM_COPY:
        LXI H,S_M_FROM
        CALL MEM_ASK
        JC MEM_CLRMSG
        SHLD MCOPY              ; from
        CALL PRINT
        TEXT " до ",0
        CALL MEM_ASK2
        JC MEM_CLRMSG
        SHLD MCOPY+2            ; to
        CALL PRINT
        TEXT " куда ",0
        CALL MEM_ASK2
        JC MEM_CLRMSG
        SHLD MCOPY+4            ; destination
        LHLD MCOPY
        XCHG
        LHLD MCOPY+2
        CALL SUB16              ; HL = length - 1
        JC .bad
        XCHG
        LHLD MCOPY+4
        DAD D                   ; last destination byte
        JC .bad
        MOV A,H
        CPI 0C0h
        JNC .bad
        INX D
        XCHG
        SHLD MCOPY+2            ; length
        LHLD MCOPY
        XCHG
        LHLD MCOPY+4
        CALL SUB16              ; HL = destination - from
        XCHG
        LHLD MCOPY+2
        MOV B,H
        MOV C,L                 ; BC = length
        CALL SUB16              ; length - (dest - from) > 0: destination inside the source
        JC .fwd
        MOV A,H
        ORA L
        JZ .fwd
        LHLD MCOPY+2            ; backwards from the last bytes
        DCX H
        XCHG
        LHLD MCOPY+4
        DAD D
        PUSH H
        LHLD MCOPY
        DAD D
        POP D
.b:     MOV A,M
        STAX D
        DCX H
        DCX D
        DCX B
        MOV A,B
        ORA C
        JNZ .b
        JMP .done
.fwd:   LHLD MCOPY+4
        XCHG
        LHLD MCOPY
.f:     MOV A,M
        STAX D
        INX H
        INX D
        DCX B
        MOV A,B
        ORA C
        JNZ .f
.done:  LXI H,S_M_DONE
        CALL MEM_MSG
        CALL MEM_ROWS
        CALL MEM_NOPIC
        JMP MEM_SHOW
.bad:   LXI H,S_M_BAD
        JMP MEM_MSG

S_M_ROM:    TEXT "ПЗУ менять нельзя: править можно ОЗУ 0000-BFFF",0
S_M_GO:     TEXT "адрес: ",0
S_M_FIND:   TEXT "поиск, байты hex (ВК без цифр - ещё раз): ",0
S_M_SEARCH: TEXT "ищу...",0
S_M_NOTF:   TEXT "не найдено",0
S_M_FOUND:  TEXT "найдено",0
S_M_FROM:   TEXT "перенос: откуда ",0
S_M_DONE:   TEXT "перенесено",0
S_M_BAD:    TEXT "неверный блок или запись вне ОЗУ 0000-BFFF",0

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
