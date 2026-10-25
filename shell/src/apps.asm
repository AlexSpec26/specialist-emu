; =====================================================================================
;  Programs. Each has a name, a draw routine (whole work area, rows 1..30) and a key
;  handler (A = key).
; =====================================================================================

APPTAB: DW N_OVERVIEW, OVERVIEW_DRAW, NOKEY
        DW N_MEMORY,   MEM_DRAW,      MEM_KEY
        DW N_TAPE,     TAPE_DRAW,     TAPE_KEY
        DW N_MONITOR,  MON_DRAW,      MON_KEY

N_OVERVIEW: TEXT "Обзор",0
N_MEMORY:   TEXT "Память",0
N_TAPE:     TEXT "Магнитофон",0
N_MONITOR:  TEXT "Монитор",0

APPS_INIT:
        LXI H,0
        SHLD MCUR
        SHLD MTOP
        XRA A
        STA MMODE
        STA MPATN
        DCR A
        STA MNIB
        LXI H,8DFFh
        SHLD MEND
        LXI H,0
        SHLD DLAST
        XRA A
        STA CMDLEN
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
MMODE   EQU APPVARS+4           ; typing goes in as: 0 hex digits, 1 characters
MNIB    EQU APPVARS+5           ; first hex digit typed while editing, FF = none
MPATN   EQU APPVARS+6           ; search pattern length (0 = none)
MPAT    EQU APPVARS+7           ; 8 bytes: search pattern
MBROW   EQU APPVARS+0Fh         ; 2: line shown in the picture
MCOPY   EQU APPVARS+11h         ; 6: transfer: from, to/length, destination
MEND    EQU APPVARS+24h         ; 2: insert/delete move the bytes up to here
MROW0   EQU 4                   ; first data row
MCOLS   EQU 33                  ; screen bytes to scroll (text columns 0..43)
MMSG    EQU 27                  ; message row

MEM_DRAW:
        CALL PRINTL
        DB 2,2
        TEXT "Адрес +0 +1 +2 +3 +4 +5 +6 +7  Текст      Биты",0
        DB 0FFh
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
        CALL PRINTL
        DB 4,24
        TEXT "◄►▲▼ НР+▲▼ курсор  F5 hex/текст  ЗБ удалить  СТР вставить",0
        DB 0FFh
        CALL MEM_HELP2
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
; data lines from line A down
MEM_ROWSFROM:
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
        STA TMP+4
        CALL MEM_POS
        JC .x
        LDA MMODE               ; hex cell marked in hex mode
        ORA A
        LDA TMP+4
        JZ .h
        XRA A
.h:     STA INV
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
.txt:   LDA MMODE               ; character cell marked in text mode
        ORA A
        LDA TMP+4
        JNZ .t
        XRA A
.t:     STA INV
        MOV A,D
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
        PUSH D
        LXI D,S_HEX
        LDA MMODE
        ORA A
        JZ .md
        LXI D,S_TXT
.md:    CALL BSTR
        POP D
        MVI C,65                ; + the two inverse codes
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
S_HEX:  TEXT 01h," ввод: HEX ",02h,0
S_TXT:  TEXT 01h," ввод: ТЕКСТ ",02h,0

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
        LDA TMP
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
        CPI 0C2h                ; F2
        JZ MEM_GO
        CPI 0C3h                ; F3
        JZ MEM_FIND
        CPI 0C4h                ; F4
        JZ MEM_COPY
        CPI 0C5h                ; F5
        JZ .mode
        CPI 0C6h                ; F6
        JZ MEM_BOUND
        CPI 1Eh                 ; ЗБ
        JZ MEM_DEL
        CPI 1Fh                 ; СТР
        JZ MEM_INS
        MOV B,A
        LDA MMODE
        ORA A
        MOV A,B
        JNZ .text
        CALL HEXVAL             ; hex: digits are typed straight in
        JNC MEM_DIGIT
        RET
.text:  CPI 0Dh                 ; text: ВК, and every character (not F keys C0-DF)
        JZ .put
        CPI 20h
        RC
        CPI 0C0h
        JC .put
        CPI 0E0h
        RC
.put:   MOV C,A
        JMP MEM_PUT
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
.mode:  XRA A
        CALL MEM_MARK
        LDA MMODE
        XRI 1
        STA MMODE
        MVI A,0FFh
        STA MNIB
        JMP MEM_SHOW

; F6: insert/delete move bytes up to this address (ОЗУ only)
MEM_BOUND:
        LXI H,S_M_BOUND
        CALL MEM_ASK
        JC MEM_CLRMSG
        MOV A,H
        CPI 0C0h
        JNC .bad
        SHLD MEND
        CALL MEM_CLRMSG
        JMP MEM_HELP2
.bad:   LXI H,S_M_BAD
        JMP MEM_MSG
S_M_BOUND: TEXT "граница сдвига: ",0

; row 25: commands and the bound
MEM_HELP2:
        LXI H,LBUF
        LXI D,S_HELP2
        CALL BSTR
        PUSH H
        LHLD MEND
        XCHG
        POP H
        CALL BHEX16
        MVI C,58
        CALL BPADTO
        MVI B,4
        MVI C,25
        JMP BPRINT
S_HELP2: TEXT "F2 адрес  F3 поиск  F4 перенос  F6 граница сдвига ",0

; ЗБ: delete the byte under the cursor; the bytes after it up to the bound move back
MEM_DEL:
        CALL MEM_SPAN
        RC
        JZ .last
        MOV D,H
        MOV E,L
        INX H
.f:     MOV A,M
        STAX D
        INX H
        INX D
        DCX B
        MOV A,B
        ORA C
        JNZ .f
        XCHG
.last:  MVI M,0
        JMP MEM_SHIFTED

; СТР: insert 00 under the cursor; the bytes up to the bound move on, the last one is lost
MEM_INS:
        CALL MEM_SPAN
        RC
        JZ .last
        LHLD MEND
        MOV D,H
        MOV E,L
        DCX H
.b:     MOV A,M
        STAX D
        DCX H
        DCX D
        DCX B
        MOV A,B
        ORA C
        JNZ .b
.last:  LHLD MCUR
        MVI M,0
MEM_SHIFTED:
        MVI A,0FFh
        STA MNIB
        CALL MEM_POS
        MOV A,C
        SUI MROW0
        CALL MEM_ROWSFROM
        CALL MEM_NOPIC
        JMP MEM_SHOW

; HL = cursor, BC = bound - cursor, Z if 0; carry (message shown) if not possible
MEM_SPAN:
        LHLD MEND
        XCHG
        LHLD MCUR
        MOV A,E
        SUB L
        MOV C,A
        MOV A,D
        SBB H
        MOV B,A
        JC .far
        MOV A,H
        CPI 0C0h
        JNC .far
        MOV A,B
        ORA C
        RET
.far:   LXI H,S_M_FAR
        CALL MEM_MSG
        STC
        RET
S_M_FAR: TEXT "курсор за границей сдвига (F6) или в ПЗУ",0

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
; write C under the cursor and step on
MEM_PUT:
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
        CALL BLKMOVE
        JC .bad
        LXI H,S_M_DONE
        CALL MEM_MSG
        CALL MEM_ROWS
        CALL MEM_NOPIC
        JMP MEM_SHOW
.bad:   LXI H,S_M_BAD
        JMP MEM_MSG

; copy MCOPY..MCOPY+2 to MCOPY+4 (overlap is fine); carry if the block is wrong or the
; copy would leave ОЗУ 0000-BFFF
BLKMOVE:
        LHLD MCOPY
        XCHG
        LHLD MCOPY+2
        CALL SUB16              ; HL = length - 1
        RC
        XCHG
        LHLD MCOPY+4
        DAD D                   ; last destination byte
        RC
        MOV A,H
        CPI 0C0h
        CMC
        RC
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
        RET                     ; carry clear (ORA)
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
        RET

S_M_ROM:    TEXT "ПЗУ менять нельзя: править можно ОЗУ 0000-BFFF",0
S_M_GO:     TEXT "адрес: ",0
S_M_FIND:   TEXT "поиск, байты hex (ВК без цифр - ещё раз): ",0
S_M_SEARCH: TEXT "ищу...",0
S_M_NOTF:   TEXT "не найдено",0
S_M_FOUND:  TEXT "найдено",0
S_M_FROM:   TEXT "перенос: откуда ",0
S_M_DONE:   TEXT "перенесено",0
S_M_BAD:    TEXT "неверный блок или запись вне ОЗУ 0000-BFFF",0

; ---------------------------------------------------------------- tape (ROM-006)
; Block format as Monitor-2 writes it: 255 x 00, E6, start, end (low byte first), the bytes,
; checksum (C82A). Reading uses the base routine C377: a key stops it (then ВК - back here).
; The only place in the shell that writes to tape (ADR-005).
TSTART  EQU APPVARS+18h         ; 2: last block read: start
TEND    EQU APPVARS+1Ah         ; 2: end
TSUM    EQU APPVARS+1Ch         ; 2: checksum from tape
TSTAT   EQU APPVARS+1Eh         ; 0 nothing read, 1 ok, 2 checksum wrong, 3 cut at 8E00
TSTH    EQU APPVARS+1Fh         ; start page, for the progress bar
TWS     EQU APPVARS+20h         ; 2: block to write: start
TWE     EQU APPVARS+22h         ; 2: end
TBAR    EQU 20*8+3              ; progress bar: a line of points in text row 20

TAPE_DRAW:
        CALL PRINTL
        DB 4,3
        TEXT "F2 загрузить с ленты   F3 записать на ленту   F5 запустить",0
        DB 4,5
        TEXT "Остановить чтение: любая клавиша, затем ВК.",0
        DB 4,6
        TEXT "Загрузка выше 8DFF не пишется: там оболочка.",0
        DB 0FFh
; rows 9-10: the last block read
TAPE_INFO:
        LXI H,LBUF
        LDA TSTAT
        ORA A
        JNZ .blk
        LXI D,S_T_NONE
        CALL BSTR
        JMP .p1
.blk:   PUSH PSW
        LXI D,S_T_LAST
        CALL BSTR
        PUSH H
        LHLD TSTART
        XCHG
        POP H
        CALL BHEX16
        MVI M,'-'
        INX H
        PUSH H
        LHLD TEND
        XCHG
        POP H
        CALL BHEX16
        LXI D,S_T_SUM
        CALL BSTR
        PUSH H
        LHLD TSUM
        XCHG
        POP H
        CALL BHEX16
        MVI M,' '
        INX H
        POP PSW
        LXI D,S_T_OK
        DCR A
        JZ .s
        LXI D,S_T_BAD
        DCR A
        JZ .s
        LXI D,S_T_CUT
.s:     CALL BSTR
.p1:    MVI C,59
        CALL BPADTO
        MVI B,4
        MVI C,9
        JMP BPRINT
S_T_NONE: TEXT "блок ещё не загружался",0
S_T_LAST: TEXT "загружено ",0
S_T_SUM:  TEXT ", сумма ",0
S_T_OK:   TEXT "верна",0
S_T_BAD:  TEXT 01h,"неверна",02h,0
S_T_CUT:  TEXT 01h,"обрезано",02h,0

TAPE_KEY:
        LDA TMP
        CPI 0C2h
        JZ TAPE_LOAD
        CPI 0C3h
        JZ TAPE_SAVE
        CPI 0C5h
        RNZ
        ; F5: run the loaded block from its start; its RET comes back to the shell
        LDA TSTAT
        DCR A
        LXI H,S_T_NORUN
        JNZ MEM_MSG
        LXI SP,STACK_TOP
        LXI H,0C800h
        PUSH H
        LHLD TSTART
        PCHL
S_T_NORUN: TEXT "нечего запускать: нет блока с верной суммой",0

; progress: a point column for each 512 bytes since the start (HL = address); keeps all
TAPE_BAR:
        MOV A,L
        ORA A
        RNZ
        PUSH B
        LDA TSTH
        MOV B,A
        MOV A,H
        SUB B
        RAR
        CPI 46
        JNC .x
        ADI 91h                 ; screen columns 1..46: inside the frame
        MOV B,A
        MVI C,TBAR
        MVI A,0FFh
        STAX B
.x:     POP B
        RET

TAPE_CLRBAR:
        MVI B,1
        MVI C,20
        CALL GOTOXY
        MVI A,' '
        MVI B,62
        JMP REPCH

; F2: read a block
TAPE_LOAD:
        CALL TAPE_CLRBAR
        LXI H,S_T_PLAY
        CALL MEM_MSG
        MVI A,0FFh              ; find the sync byte, then the start address
        CALL B_TAPE_IN
        MOV L,A
        MVI A,08h
        CALL B_TAPE_IN
        MOV H,A
        SHLD TSTART
        MOV A,H
        STA TSTH
        MVI A,08h
        CALL B_TAPE_IN
        MOV E,A
        MVI A,08h
        CALL B_TAPE_IN
        MOV D,A
        XCHG
        SHLD TEND
        XCHG
        MVI C,1                 ; 1 = ok so far, 3 = cut
.l:     MVI A,08h
        CALL B_TAPE_IN
        MOV B,A
        MOV A,H
        CPI 8Eh
        JNC .cut
        MOV M,B
        JMP .p
.cut:   MVI C,3
.p:     CALL TAPE_BAR
        CALL B_CMP_HL_DE
        INX H
        JNZ .l
        MVI A,08h
        CALL B_TAPE_IN
        MOV L,A
        MVI A,08h
        CALL B_TAPE_IN
        MOV H,A
        SHLD TSUM
        CALL TAPE_PPI
        MOV A,C
        STA TSTAT
        CPI 3
        JZ .done
        LHLD TEND
        XCHG
        LHLD TSTART
        CALL M_CHECKSUM
        LHLD TSUM
        MOV A,L
        CMP C
        JNZ .bad
        MOV A,H
        CMP B
        JZ .done
.bad:   MVI A,2
        STA TSTAT
.done:  CALL MEM_CLRMSG
        JMP TAPE_INFO
S_T_PLAY: TEXT "включите воспроизведение...",0

; tape output back to rest, as Monitor-2 does after the tape
TAPE_PPI:
        LXI H,PPI_CTL
        MVI M,0Dh
        MVI M,0Ch
        RET

; F3: write a block
TAPE_SAVE:
        LXI H,S_T_FROM
        CALL MEM_ASK
        JC MEM_CLRMSG
        SHLD TWS
        CALL PRINT
        TEXT " конец ",0
        CALL MEM_ASK2
        JC MEM_CLRMSG
        SHLD TWE
        XCHG
        LHLD TWS
        MOV A,E
        SUB L
        MOV A,D
        SBB H
        LXI H,S_M_BAD
        JC MEM_MSG
        LXI H,S_T_REC
        CALL MEM_MSG
        CALL GETKEY
        CPI 0Dh
        JNZ MEM_CLRMSG
        LXI H,S_T_WRITING
        CALL MEM_MSG
        CALL TAPE_CLRBAR
        LHLD TWS
        MOV A,H
        STA TSTH
        XCHG
        LHLD TWE
        XCHG
        CALL M_CHECKSUM
        PUSH B
        MVI B,0FFh              ; leader
.z:     XRA A
        CALL B_TAPE_OUT
        DCR B
        JNZ .z
        MVI A,0E6h
        CALL B_TAPE_OUT
        LHLD TWE
        XCHG
        LHLD TWS
        MOV A,L
        CALL B_TAPE_OUT
        MOV A,H
        CALL B_TAPE_OUT
        MOV A,E
        CALL B_TAPE_OUT
        MOV A,D
        CALL B_TAPE_OUT
.d:     MOV A,M
        CALL B_TAPE_OUT
        CALL TAPE_BAR
        CALL B_CMP_HL_DE
        INX H
        JNZ .d
        POP H
        MOV A,L
        CALL B_TAPE_OUT
        MOV A,H
        CALL B_TAPE_OUT
        CALL TAPE_PPI
        LXI H,S_T_DONE
        JMP MEM_MSG
S_T_FROM:    TEXT "записать: начало ",0
S_T_REC:     TEXT "включите запись и нажмите ВК (другая клавиша - отмена)",0
S_T_WRITING: TEXT "запись...",0
S_T_DONE:    TEXT "записано",0

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
