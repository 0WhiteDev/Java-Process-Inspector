.text
.global jpi_worker_start
.global jpi_worker_end
.hidden jpi_worker_start
.hidden jpi_worker_end
.type jpi_worker_start,@function
jpi_worker_start:
    endbr64
    push %r12
    mov %rdi,%r12
    mov 32(%r12),%rdi
    mov $2,%esi
    call *0(%r12)
    test %rax,%rax
    jz .Lload_failed
    mov %rax,64(%r12)
    mov %rax,%rdi
    mov 72(%r12),%rsi
    call *8(%r12)
    test %rax,%rax
    jz .Llegacy
    lea 80(%r12),%rdi
    mov 24(%r12),%rsi
    call *%rax
    mov %eax,56(%r12)
    cmpl $0,80(%r12)
    jne .Ldone
    mov 64(%r12),%rdi
    call *16(%r12)
    jmp .Ldone
.Llegacy:
    cmpq $0,24(%r12)
    jne .Lsymbol_failed
    mov 64(%r12),%rdi
    mov 40(%r12),%rsi
    call *8(%r12)
    test %rax,%rax
    jz .Lsymbol_failed
    call *%rax
    mov %eax,56(%r12)
    jmp .Ldone
.Lsymbol_failed:
    movl $-3,56(%r12)
    mov 64(%r12),%rdi
    call *16(%r12)
    jmp .Ldone
.Lload_failed:
    movl $-2,56(%r12)
.Ldone:
    movl $1,60(%r12)
    xor %eax,%eax
    pop %r12
    ret
jpi_worker_end:
.size jpi_worker_start,.-jpi_worker_start
.section .note.GNU-stack,"",@progbits
