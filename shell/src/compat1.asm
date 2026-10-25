; =====================================================================================
;  Monitor-2 compatible services. They sit at the same addresses as in Monitor-2
;  (CE1D, CE5C, CE9D, CEC8), because a few programs call them there directly.
; =====================================================================================

; C81E: cursor position (H = X, L = Y in pixels)
M_GETCUR:
        LHLD V_CURSOR
        RET

; C82A: monitor checksum of HL..DE -> BC
M_CHECKSUM:
        LXI B,0
.lp:    MOV A,M
        ADD C
        MOV C,A
        PUSH PSW
        CALL B_CMP_HL_DE
        JZ .last
        POP PSW
        MOV A,B
        ADC M
        MOV B,A
        INX H
        JMP .lp
.last:  POP PSW
        RET

M_SETTOP:
        SHLD V_MEMTOP
        RET
M_GETTOP:
        LHLD V_MEMTOP
        RET



        ; a program fits here; Monitor-2's own bytes must stay from CD21 on, because some games
        ; read ROM bytes as data (Piton 3 reads CD21-CD28)
