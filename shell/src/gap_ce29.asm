; placed in a free gap of the ROM (ADR-017); belongs to «Отладка» and «Магнитофон»
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


; 255 x 00 before a block
TAPE_LEAD:
        MVI B,0FFh
.z:     XRA A
        CALL B_TAPE_OUT
        DCR B
        JNZ .z
        RET

