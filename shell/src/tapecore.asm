; =====================================================================================
;  Tape engine shared by both ROMs: Monitor-2's block format, with or without a file name.
; =====================================================================================
TVARS   EQU 8E50h               ; (APPVARS is full: «Отладка» uses most of it)
TSTART  EQU TVARS+0             ; 2: last block read: start
TEND    EQU TVARS+2             ; 2: end
TSUM    EQU TVARS+4             ; 2: checksum from tape
TSTAT   EQU TVARS+6             ; 0 nothing read, 1 ok, 2 checksum wrong, 3 cut at 8E00
TSTH    EQU TVARS+7             ; start page, for the progress bar
TWS     EQU TVARS+8             ; 2: block to write: start
TWE     EQU TVARS+0Ah           ; 2: end
TNAME   EQU TVARS+0Ch           ; 18: file name (as Monitor-2's I and O), 00 = none
TBAR    EQU 20*8+3              ; progress bar: a line of points in text row 20

; read a block (a name header D9 D9 D9 + 17 bytes first, if there is one) -> TSTART, TEND,
; TSUM, TNAME, TSTAT (1 ok, 2 checksum wrong, 3 cut at 8E00). A key stops it (base ROM).
TAPE_RD:
        XRA A
        STA TNAME
.blk:   MVI A,0FFh              ; find the sync byte, then the start address
        CALL B_TAPE_IN
        MOV L,A
        MVI A,08h
        CALL B_TAPE_IN
        MOV H,A
        MOV A,L                 ; D9 D9 D9: Monitor-2's file name (command O), then the block
        ANA H
        CPI 0D9h
        JNZ .noname
        MOV A,L
        XRA H
        JNZ .noname
        MVI A,08h
        CALL B_TAPE_IN
        LXI H,TNAME             ; 17 bytes, as Monitor-2's I reads them
        MVI B,17
.n:     MVI A,08h
        CALL B_TAPE_IN
        MOV M,A
        INX H
        DCR B
        JNZ .n
        MVI M,0
        JMP .blk
.noname:
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
        RZ
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
        RZ
.bad:   MVI A,2
        STA TSTAT
        RET

; write TWS..TWE; with the name at INBUF (00 = none) the header goes first, as Monitor-2's O
TAPE_WR:
        LHLD TWS
        MOV A,H
        STA TSTH
        XCHG
        LHLD TWE
        XCHG
        CALL M_CHECKSUM
        PUSH B
        LDA INBUF               ; a name: the header as Monitor-2's O writes it
        ORA A
        JZ .blk
        CALL TAPE_LEAD
        MVI A,0E6h
        CALL B_TAPE_OUT
        MVI A,0D9h
        CALL B_TAPE_OUT
        CALL B_TAPE_OUT
        CALL B_TAPE_OUT
        LXI H,INBUF
.nm:    MOV A,M
        ORA A
        JZ .ne
        CALL B_TAPE_OUT
        INX H
        JMP .nm
.ne:    CALL TAPE_LEAD
        CALL TAPE_LEAD
.blk:   CALL TAPE_LEAD
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
        RET

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

; tape output back to rest, as Monitor-2 does after the tape
TAPE_PPI:
        LXI H,PPI_CTL
        MVI M,0Dh
        MVI M,0Ch
        RET

