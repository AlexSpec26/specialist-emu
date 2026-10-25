; part of the Monitor-2 compatibility area, split so that each ROM fills the gaps itself
        ASSERT $ <= 0CD21h, "code runs into CD21 (Monitor-2 bytes read by games)"
        ORG 0CD21h
        ; Piton 3 reads CD21-CD28 of the ROM as data: Monitor-2's bytes stay there
        INCBIN "../base/spec_m2.rom", 0D21h, 8
