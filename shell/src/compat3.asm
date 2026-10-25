; part of the Monitor-2 compatibility area, split so that each ROM fills the gaps itself
        ASSERT $ <= 0CE1Dh, "code runs into CE1D (fixed Monitor-2 routines)"
        ORG 0CE1Dh
; C818: print string (HL) up to 00
M_PUTS: MOV A,M
        MOV C,A
        CPI 00h
        RZ
        CALL M_PUTC
        INX H
        JMP M_PUTS

