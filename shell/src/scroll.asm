; ---------------------------------------------------------------- scrolling (ROM-001)
; Text rows move by copying screen memory; the stack serves as a fast second pointer (the
; Specialist has no interrupts). B = top row, C = bottom row, B < C. Changes A, BC, DE, HL.
; SCROLLUP: rows B+1..C move up one row, row C is cleared.
SCROLLUP:
        LXI H,0
        DAD SP
        SHLD SSAVE
        MOV A,E
        ADI 90h
        STA SCREND
        MOV A,C
        SUB B
        MOV C,A                 ; text rows to move
        MOV A,B
        ADD A
        ADD A
        ADD A
        MOV B,A                 ; first destination byte in a column
        MVI H,90h
.col:   MOV A,B
        ADI 8
        MOV L,A
        SPHL                    ; SP = source
        MOV L,B                 ; HL = destination
        MOV A,C
.row:
        POP D
        MOV M,E
        INR L
        MOV M,D
        INR L
        POP D
        MOV M,E
        INR L
        MOV M,D
        INR L
        POP D
        MOV M,E
        INR L
        MOV M,D
        INR L
        POP D
        MOV M,E
        INR L
        MOV M,D
        INR L
        DCR A
        JNZ .row
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        MOV M,A
        INR L
        INR H
        LDA SCREND
        CMP H
        JNZ .col
        LHLD SSAVE
        SPHL
        RET

; SCROLLDN: rows B..C-1 move down one row, row B is cleared.
SCROLLDN:
        LXI H,0
        DAD SP
        SHLD SSAVE
        MOV A,E
        ADI 90h
        STA SCREND
        MOV A,C
        SUB B
        MOV E,A
        MOV A,C
        ADD A
        ADD A
        ADD A
        DCR A
        MOV B,A                 ; last source byte in a column
        MOV C,E                 ; text rows to move
        MVI H,90h
.col:   MOV L,B
        LXI D,9
        DAD D
        SPHL                    ; SP = one past the last destination byte
        LXI D,-9
        DAD D                   ; HL = last source byte
        MOV A,C
.row:
        MOV D,M
        DCR L
        MOV E,M
        DCR L
        PUSH D
        MOV D,M
        DCR L
        MOV E,M
        DCR L
        PUSH D
        MOV D,M
        DCR L
        MOV E,M
        DCR L
        PUSH D
        MOV D,M
        DCR L
        MOV E,M
        DCR L
        PUSH D
        DCR A
        JNZ .row
        LXI D,0
        PUSH D
        PUSH D
        PUSH D
        PUSH D
        INR H
        LDA SCREND
        CMP H
        JNZ .col
        LHLD SSAVE
        SPHL
        RET

