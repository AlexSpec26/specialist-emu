; MOUSE.COM — test program for the touch mouse (DosNativeTest): resets the mouse driver, then keeps
; copying INT 33h AX=3 — buttons, x, y — to 0000:0510 (buttons, ORed: every button ever seen),
; 0512 (x), 0514 (y), and sets 0000:0516 = 1.
; Build: nasm -f bin dos/tools/mouse.asm -o dos/tools/MOUSE.COM  (GPL-2.0-or-later, see dos/COPYING)
        org 100h
        xor ax, ax
        int 33h
        xor ax, ax
        mov es, ax
        mov byte [es:0510h], 0
again:  mov ax, 3
        int 33h
        or [es:0510h], bl
        mov [es:0512h], cx
        mov [es:0514h], dx
        mov byte [es:0516h], 1
        sti
        hlt
        jmp again
