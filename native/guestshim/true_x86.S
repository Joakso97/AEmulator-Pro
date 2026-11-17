 
 
 
    .text
    .globl _start
_start:
    xorl %ebx, %ebx
    movl $1, %eax
    int $0x80
