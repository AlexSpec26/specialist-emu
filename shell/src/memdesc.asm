; «Память»: what an address is (placed in a free gap, see compat.asm)
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
S_D_BUF:   TEXT "ОЗУ: буферы и стек оболочки",0
S_D_SVAR:  TEXT "ОЗУ: переменные оболочки",0
S_D_MVAR:  TEXT "ОЗУ: переменные монитора",0
S_D_SCR:   TEXT "экран: столбец ",0
S_D_LINE:
S_D_ROW:   TEXT ", строка ",0
S_D_BASE:  TEXT "ПЗУ: базовые подпрограммы",0
S_D_FONT:  TEXT "ПЗУ: шрифт, символ ",0
S_D_GLYPH: TEXT "ПЗУ: доп. символ ",0
S_D_OF8:   TEXT " из 8",0
S_D_ENTRY: TEXT "ПЗУ: входы монитора",0
S_D_SHELL: TEXT "ПЗУ: оболочка",0
S_D_NONE:  TEXT "нет памяти",0
S_D_PPI:   TEXT "клавиатура ВВ55, регистр ",0
S_D_CTL:   TEXT "упр.",0

