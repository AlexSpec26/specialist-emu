; =====================================================================================
;  Programs. Each has a name, a draw routine (whole work area, rows 1..30) and a key
;  handler (A = key). Stage 1: overview; the others are placeholders.
; =====================================================================================

APPTAB: DW N_OVERVIEW, OVERVIEW_DRAW, OVERVIEW_KEY
        DW N_MEMORY,   MEM_DRAW,      MEM_KEY
        DW N_DEBUG,    DBG_DRAW,      DBG_KEY
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

; ---------------------------------------------------------------- debugger (ROM-003)
; The program runs on the real processor. Breakpoints (F8/F9) are CALL DBG_TRAPC written over
; 3 bytes (most programs load at 0000, so the RST vectors are their code). A single step puts
; RST 7 (FF) on the next command and on the branch target, with JMP DBG_TRAP at 0038-003A
; only for that one command. All changed bytes are saved and put back at the stop. Only for
; code in ОЗУ (0000-BFFF).
R_AF    EQU APPVARS+18h         ; registers of the program, in this order
R_BC    EQU APPVARS+1Ah
R_DE    EQU APPVARS+1Ch
R_HL    EQU APPVARS+1Eh
R_SP    EQU APPVARS+20h
R_PC    EQU APPVARS+22h
DSEL    EQU APPVARS+24h         ; selected register 0..5
DMODE   EQU APPVARS+25h         ; 1 = after this stop put the breakpoints and run on
DEND    EQU APPVARS+26h         ; 1 = the program returned into the debugger
DBP     EQU APPVARS+27h         ; 4 breakpoints, FFFF = none
DPLN    EQU APPVARS+2Fh         ; planted traps
DPL     EQU APPVARS+30h         ; 4 x (address, 3 saved bytes)
DVEC    EQU APPVARS+44h         ; saved 0038-003A
DN1     EQU APPVARS+47h         ; step: next address and branch target
DN2     EQU APPVARS+49h
DVON    EQU APPVARS+4Bh         ; 1 = the vector at 0038 is ours
DKIND   EQU APPVARS+4Ch         ; what DBG_PLANT writes: 0 RST 7, 1 CALL DBG_TRAPC
DSTACK  EQU 8DFEh               ; program stack after F2; holds the return into the debugger

DBG_DRAW:
        MVI B,0
        MVI C,1
        MVI D,63
        MVI E,30
        MVI A,1
        CALL FRAME
        MVI B,2
        MVI C,1
        CALL PRINTAT
        TEXT " Отладка ",0
        MVI B,36
        MVI C,4
        CALL PRINTAT
        TEXT "Стек",0
        MVI B,4
        MVI C,4
        CALL PRINTAT
        TEXT "Код от PC (",01h,"►",02h," PC, * точка)",0
        MVI B,4
        MVI C,24
        CALL PRINTAT
        TEXT "◄► регистр  ВК изменить  F2 PC=адрес  F8 точка останова",0
        MVI B,4
        MVI C,25
        CALL PRINTAT
        TEXT "F5 шаг  F6 шаг без захода в CALL  F9 пуск до точки",0
        CALL DBG_REGS
        CALL DBG_CODE
        CALL DBG_SIDE
        LDA DEND
        ORA A
        RZ
        XRA A
        STA DEND
        LXI H,S_G_END
        JMP MEM_MSG

; row 2: registers, the selected one inverted; row 3: flags
DBG_REGS:
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        MVI M,' '
        INX H
        LXI D,R_AF
        MVI B,0
.r:     MOV A,B
        PUSH B
        LXI B,S_G_NAMES
        ADD A
        ADD C
        MOV C,A                 ; names never cross a page (ASSERT below)
        LDAX B
        MOV M,A
        INX H
        INX B
        LDAX B
        MOV M,A
        INX H
        MVI M,'='
        INX H
        POP B
        LDA DSEL
        CMP B
        JNZ .n1
        MVI M,01h
        INX H
.n1:    CALL BHEXW
        LDA DSEL
        CMP B
        JNZ .n2
        MVI M,02h
        INX H
.n2:    MVI M,' '
        INX H
        MVI M,' '
        INX H
        INR B
        MOV A,B
        CPI 6
        JNZ .r
        MVI C,65                ; 63 columns + the two inverse codes
        CALL BPADTO
        MVI B,0
        MVI C,2
        CALL BPRINT
        ; flags S Z AC P C: letter when set
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        LXI D,S_G_FLAGS
        CALL BSTR
        LDA R_AF
        MOV B,A
        LXI D,S_G_FL
.f:     LDAX D
        ORA A
        JZ .fe
        MOV C,A                 ; bit mask
        INX D
        LDAX D
        INX D
        MOV M,A
        MOV A,B
        ANA C
        JNZ .fs
        MVI M,C_DOT
.fs:    INX H
        MVI M,' '
        INX H
        JMP .f
.fe:    MVI C,32
        CALL BPADTO
        MVI B,0
        MVI C,3
        JMP BPRINT
S_G_NAMES: TEXT "AFBCDEHLSPPC"
        ASSERT HIGH(S_G_NAMES) == HIGH($), "S_G_NAMES crosses a page"
S_G_FLAGS: TEXT "  флаги: ",0
S_G_FL: DB 80h,'S',40h,'Z',10h,'A',04h,'P',01h,'C',0

; rows 5..20: 16 commands from PC as bytes
DBG_CODE:
        LHLD R_PC
        XCHG
        MVI C,5
.l:     PUSH B
        LXI H,LBUF
        MVI M,C_VL2
        INX H
        MVI M,' '
        INX H
        LHLD R_PC
        MOV A,L
        CMP E
        JNZ .np
        MOV A,H
        CMP D
        JNZ .np
        LXI H,LBUF+2
        MVI M,0FBh              ; ►
        JMP .bp
.np:    LXI H,LBUF+2
        MVI M,' '
.bp:    INX H
        CALL DBG_ISBP
        MVI M,' '
        JNZ .nb
        MVI M,'*'
.nb:    INX H
        CALL BHEX16
        MVI M,' '
        INX H
        MVI M,' '
        INX H
        LDAX D
        CALL OPINFO
.b:     LDAX D
        CALL BHEX8
        MVI M,' '
        INX H
        INX D
        DCR B
        JNZ .b
        MVI C,32
        CALL BPADTO
        POP B
        PUSH B
        MVI B,0
        PUSH D
        CALL BPRINT
        POP D
        POP B
        INR C
        MOV A,C
        CPI 21
        JNZ .l
        RET

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

; right side: stack (8 words), memory at HL, breakpoints
DBG_SIDE:
        LHLD R_SP
        XCHG
        MVI C,5
.s:     LXI H,LBUF
        CALL BHEX16
        MVI M,':'
        INX H
        MVI M,' '
        INX H
        CALL BHEXW
        MVI B,36
        PUSH D
        CALL BPRINT
        POP D
        INR C
        MOV A,C
        CPI 13
        JNZ .s
        LXI H,LBUF
        LXI D,S_G_HL
        CALL BSTR
        MVI B,36
        MVI C,14
        CALL BPRINT
        LHLD R_HL
        XCHG
        LXI H,LBUF
        MVI B,8
.m:     LDAX D
        CALL BHEX8
        MVI M,' '
        INX H
        INX D
        DCR B
        JNZ .m
        MVI B,36
        MVI C,15
        CALL BPRINT
        LXI H,LBUF
        LXI D,S_G_BPS
        CALL BSTR
        MVI B,36
        MVI C,17
        CALL BPRINT
        LXI H,LBUF
        LXI D,DBP
        MVI B,4
.p:     LDAX D
        MOV C,A
        INX D
        LDAX D
        DCX D
        ANA C
        INR A
        JNZ .ph
        MVI M,'-'               ; empty
        INX H
        MVI M,'-'
        INX H
        MVI M,'-'
        INX H
        MVI M,'-'
        INX H
        JMP .pn
.ph:    CALL BHEXW
        DCX D
        DCX D
.pn:    MVI M,' '
        INX H
        INX D
        INX D
        DCR B
        JNZ .p
        MVI B,36
        MVI C,18
        JMP BPRINT
S_G_HL:  TEXT "(HL):",0
S_G_BPS: TEXT "Точки останова:",0
S_G_END: TEXT "программа вернулась в отладку (RET)",0

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

; ---------------------------------------------------------------- debugger: keys
DBG_KEY:
        LDA TMP
        CPI 18h                 ; →
        JZ .next
        CPI 08h                 ; ←
        JZ .prev
        CPI 0Dh
        JZ DBG_EDIT
        CPI 0C2h                ; F2
        JZ DBG_GO_ADDR
        CPI 0C5h                ; F5
        JZ .step
        CPI 0C6h                ; F6
        JZ .over
        CPI 0C8h                ; F8
        JZ DBG_BREAK
        CPI 0C9h                ; F9
        JZ DBG_RUN
        RET
.next:  LDA DSEL
        INR A
        CPI 6
        JC .sel
        XRA A
        JMP .sel
.prev:  LDA DSEL
        DCR A
        JP .sel
        MVI A,5
.sel:   STA DSEL
        JMP DBG_REGS
.step:  XRA A
        JMP DBG_STEP
.over:  MVI A,1
        JMP DBG_STEP

; ВК: new value of the selected register
DBG_EDIT:
        LXI H,S_G_VAL
        CALL MEM_ASK
        JC MEM_CLRMSG
        XCHG
        LDA DSEL
        ADD A
        MOV C,A
        MVI B,0
        LXI H,R_AF
        DAD B
        MOV M,E
        INX H
        MOV M,D
        CALL MEM_CLRMSG
        CALL DBG_REGS
        CALL DBG_CODE
        JMP DBG_SIDE
S_G_VAL: TEXT "новое значение: ",0

; F2: PC = address, SP = DSTACK with a return into the debugger
DBG_GO_ADDR:
        LXI H,S_M_GO
        CALL MEM_ASK
        JC MEM_CLRMSG
        SHLD R_PC
        CALL MEM_CLRMSG
        CALL DBG_SETSP
        CALL DBG_REGS
        CALL DBG_CODE
        JMP DBG_SIDE
DBG_SETPC:
        LXI H,0
        SHLD R_PC
DBG_SETSP:
        LXI H,DBG_DONE
        SHLD DSTACK
        LXI H,DSTACK
        SHLD R_SP
        RET

; F8: add a breakpoint, or remove it if it is there
DBG_BREAK:
        LXI H,S_G_BP
        CALL MEM_ASK
        JC MEM_CLRMSG
        XCHG
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
        JMP .done
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
        LXI H,S_G_FULL
        JMP MEM_MSG
.put:   MOV M,D
        DCX H
        MOV M,E
.done:  CALL MEM_CLRMSG
        CALL DBG_CODE
        JMP DBG_SIDE
S_G_BP:   TEXT "точка останова (есть - снять): ",0
S_G_FULL: TEXT "точек уже 4",0

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
        JMP MEM_MSG
S_G_VEC: TEXT "команда у адреса 0038 (там вектор шага): шаг нельзя",0
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

; JMP DBG_TRAP at 0038 (the RST 7 vector), old bytes saved
DBG_VEC:
        MVI A,1
        STA DVON
        LXI H,0038h
        LXI D,DVEC
        MVI B,3
.s:     MOV A,M
        STAX D
        INX H
        INX D
        DCR B
        JNZ .s
        LXI H,DBG_TRAP
        SHLD 0039h
        MVI A,0C3h
        STA 0038h
        RET

; trap at HL (only ОЗУ, not twice, FFFF = none): RST 7 or CALL DBG_TRAPC (DKIND); 3 bytes
; are saved. Keeps B.
DBG_PLANT:
        MOV A,H
        CPI 0C0h
        RNC
        XCHG
        LXI H,DPLN
        MOV C,M
        INX H
        INR C
.c:     DCR C
        JZ .new
        MOV A,M
        CMP E
        JNZ .c2
        INX H
        MOV A,M
        DCX H
        CMP D
        RZ
.c2:    INX H
        INX H
        INX H
        INX H
        INX H
        JMP .c
.new:   MOV M,E
        INX H
        MOV M,D
        INX H
        MVI C,3
.s:     LDAX D
        MOV M,A
        INX H
        INX D
        DCR C
        JNZ .s
        DCX D
        DCX D
        DCX D
        LDA DKIND
        ORA A
        MVI A,0FFh
        JZ .w
        MVI A,0CDh
        STAX D
        INX D
        MVI A,LOW(DBG_TRAPC)
        STAX D
        INX D
        MVI A,HIGH(DBG_TRAPC)
.w:     STAX D
        LXI H,DPLN
        INR M
        RET

; put back the planted bytes (last first) and the vector
DBG_UNPLANT:
        LDA DPLN
        ORA A
        JZ .v
        MOV C,A
        ADD A
        ADD A
        ADD C
        MOV E,A
        MVI D,0
        LXI H,DPL-5
        DAD D                   ; last entry
.l:     MOV E,M
        INX H
        MOV D,M
        INX H
        MVI B,3
.r:     MOV A,M
        STAX D
        INX H
        INX D
        DCR B
        JNZ .r
        LXI D,-10
        DAD D
        DCR C
        JNZ .l
        XRA A
        STA DPLN
.v:     LDA DVON
        ORA A
        RZ
        XRA A
        STA DVON
        LXI H,DVEC
        LXI D,0038h
        MVI B,3
.w:     MOV A,M
        STAX D
        INX H
        INX D
        DCR B
        JNZ .w
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
.stop:  MVI A,2                 ; show the debugger
        STA CURAPP
        LDA RUNMASK
        ORI 4
        STA RUNMASK
        JMP MAIN

; the program's last RET comes here (pushed by F2)
DBG_DONE:
        SHLD R_HL
        PUSH PSW
        MVI A,1
        STA DEND
        POP PSW
        LXI H,DBG_DONE
        JMP DBG_SAVE

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
