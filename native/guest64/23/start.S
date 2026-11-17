 
	.text
	.globl _start
	.type _start,%function
_start:
	mov x29, #0
	mov x30, #0
	mov x0, sp
	bl aemu_main
	brk #1

 
	.globl sc
	.type sc,%function
sc:
	mov x8, x0
	mov x0, x1
	mov x1, x2
	mov x2, x3
	mov x3, x4
	mov x4, x5
	mov x5, x6
	svc #0
	ret

 
	.globl enter_guest
	.type enter_guest,%function
enter_guest:
	mov x16, x0
	mov sp, x1
	mov x0, #0
	mov x1, #0
	mov x2, #0
	mov x3, #0
	mov x29, #0
	mov x30, #0
	br x16
