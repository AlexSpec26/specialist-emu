; placed in a free gap of the ROM (ADR-017); belongs to «Отладка»
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

