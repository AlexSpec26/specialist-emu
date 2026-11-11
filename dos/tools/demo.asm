; test program: VGA 320x200, a line moving with the BIOS clock, the colour set by the last key
org 100h
    mov ax,0013h
    int 10h
    mov ax,0A000h
    mov es,ax
    mov bl,15            ; colour
again:
    mov ah,1
    int 16h
    jz nokey
    mov ah,0
    int 16h
    mov bl,ah            ; scan code as colour
    cmp al,27
    je quit
nokey:
    push ds
    xor ax,ax
    mov ds,ax
    mov ax,[046Ch]       ; BIOS ticks (18.2 Hz)
    pop ds
    xor dx,dx
    mov cx,200
    div cx               ; dx = tick % 200
    mov ax,320
    mul dx
    mov di,ax
    mov al,bl
    mov cx,320
    rep stosb
    ; speaker click every tick change
    in al,61h
    xor al,2
    out 61h,al
    jmp again
quit:
    mov ax,0003h
    int 10h
    ret
