; placed in a free gap of the ROM (ADR-017); belongs to «Отладка»
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

