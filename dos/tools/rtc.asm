; RTC.COM — test program for the CMOS clock (DosNativeTest): keeps copying the real-time clock's
; seconds, minutes and hours (ports 70h/71h, BCD) to 0000:0500..0502 and sets 0000:0503 = 1.
; Build: nasm -f bin dos/tools/rtc.asm -o dos/tools/RTC.COM  (GPL-2.0-or-later, see dos/COPYING)
        org 100h
        xor ax, ax
        mov es, ax
again:  mov al, 0
        out 70h, al
        in al, 71h
        mov [es:0500h], al
        mov al, 2
        out 70h, al
        in al, 71h
        mov [es:0501h], al
        mov al, 4
        out 70h, al
        in al, 71h
        mov [es:0502h], al
        mov byte [es:0503h], 1
        sti
        hlt
        jmp again
