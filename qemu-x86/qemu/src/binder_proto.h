// SPDX-License-Identifier: GPL-2.0-or-later
//
// ★ PART OF A MODIFIED QEMU. This file was ADDED to the QEMU sources
// (linux-user) by the ISTRATII_TECH SENSES project and is compiled into
// qemu-arm, therefore it is distributed under the terms of the GNU General
// Public License version 2 or (at your option) any later version.
//
// Full licence text: see COPYING in the project root.
// Everything changed in QEMU is listed in NOTICE.
//
// Copyright (c) 2026 istratiitech <https://github.com/istratiitech>
// Changed: 2026-08-29 … 2026-10-11

// binder_proto.h — определения драйвера binder и протокол «шим ↔ binderd».
//
// ★ Происхождение. Это форк файла из более ранней нашей работы, где
// тот же демон уже поднимал полный стек Android 7 — SurfaceFlinger,
// зиготу и system_server. Протокол «шим ↔ binderd» перенесён без смысловых
// правок; переименованы только метки: на проводе теперь «sb00» и «SB», в
// именах — префикс SB_ / sb_. Правки по-прежнему могут ходить между проектами
// в обе стороны, а лечить одну и ту же гонку дважды не приходится.
//
// ★ Объявления UAPI. The kernel-interface declarations in the section
// "UAPI binder" below follow include/uapi/linux/android/binder.h of the Linux
// kernel (GPL-2.0 WITH Linux-syscall-note); the rest of this file, and the
// "shim <-> binderd" protocol, are ours.
//
// ★ Единственное расхождение — РАЗРЯДНОСТЬ ГОСТЯ. Там гость 64-битный (Android
// 7, версия протокола binder 8), здесь 32-битный (Android 2.3, версия 7).
// Отличаются только ядерные структуры UAPI ниже; сам проводной протокол несёт
// 64-битные ptr/cookie и явные размеры, поэтому демону разрядность безразлична.
//
// Числа сверены с ПРОШИВКОЙ, а не с заголовками AOSP: в libbinder.so из ROM'а
// найдены ровно BINDER_WRITE_READ=0xc0186201 (значит sizeof=24),
// SET_MAX_THREADS=0x40046205, SET_CONTEXT_MGR=0x40046207,
// THREAD_EXIT=0x40046208, VERSION=0xc0046209.
//
// Зачем свой binder. Ядерного домена binder гостю не достать: диспетчеры
// контекста заняты хозяином, а binderfs требует прав. Но гостю binder нужен
// ТОЛЬКО внутри себя — с хозяйским он не разговаривает никогда, значит
// достаточно подделать драйвер для своих же процессов.
//
// Раскладка проверяется static_assert'ами: расхождение падает на сборке, а не
// портит память молча.

#ifndef GUEST_BINDER_PROTO_H
#define GUEST_BINDER_PROTO_H

#include <stdint.h>
#include <sys/ioctl.h>
#include <sys/types.h>

 

 
 
typedef uint32_t binder_size_t;
typedef uint32_t binder_uintptr_t;

struct binder_write_read {
    binder_size_t write_size;
    binder_size_t write_consumed;
    binder_uintptr_t write_buffer;
    binder_size_t read_size;
    binder_size_t read_consumed;
    binder_uintptr_t read_buffer;
};

struct binder_version { int32_t protocol_version; };

#define BINDER_CURRENT_PROTOCOL_VERSION 7       

#define BINDER_WRITE_READ        _IOWR('b', 1, struct binder_write_read)
#define BINDER_SET_IDLE_TIMEOUT  _IOW('b', 3, int64_t)
#define BINDER_SET_MAX_THREADS   _IOW('b', 5, uint32_t)
#define BINDER_SET_IDLE_PRIORITY _IOW('b', 6, int32_t)
#define BINDER_SET_CONTEXT_MGR   _IOW('b', 7, int32_t)
#define BINDER_THREAD_EXIT       _IOW('b', 8, int32_t)
#define BINDER_VERSION           _IOWR('b', 9, struct binder_version)

struct binder_transaction_data {
    union { uint32_t handle; binder_uintptr_t ptr; } target;
    binder_uintptr_t cookie;
    uint32_t code;
    uint32_t flags;
    pid_t sender_pid;
    uid_t sender_euid;
    binder_size_t data_size;
    binder_size_t offsets_size;
    union {
        struct { binder_uintptr_t buffer; binder_uintptr_t offsets; } ptr;
        uint8_t buf[8];
    } data;
};

struct binder_ptr_cookie { binder_uintptr_t ptr; binder_uintptr_t cookie; };

struct binder_handle_cookie {
    uint32_t handle;
    binder_uintptr_t cookie;
} __attribute__((packed));

 
#define TF_ONE_WAY     0x01
#define TF_ROOT_OBJECT 0x04
#define TF_STATUS_CODE 0x08
#define TF_ACCEPT_FDS  0x10

 
enum binder_driver_command_protocol {
    BC_TRANSACTION = _IOW('c', 0, struct binder_transaction_data),
    BC_REPLY = _IOW('c', 1, struct binder_transaction_data),
    BC_ACQUIRE_RESULT = _IOW('c', 2, int32_t),
    BC_FREE_BUFFER = _IOW('c', 3, binder_uintptr_t),
    BC_INCREFS = _IOW('c', 4, uint32_t),
    BC_ACQUIRE = _IOW('c', 5, uint32_t),
    BC_RELEASE = _IOW('c', 6, uint32_t),
    BC_DECREFS = _IOW('c', 7, uint32_t),
    BC_INCREFS_DONE = _IOW('c', 8, struct binder_ptr_cookie),
    BC_ACQUIRE_DONE = _IOW('c', 9, struct binder_ptr_cookie),
    BC_ATTEMPT_ACQUIRE = _IOW('c', 10, struct binder_ptr_cookie),
    BC_REGISTER_LOOPER = _IO('c', 11),
    BC_ENTER_LOOPER = _IO('c', 12),
    BC_EXIT_LOOPER = _IO('c', 13),
    BC_REQUEST_DEATH_NOTIFICATION = _IOW('c', 14, struct binder_handle_cookie),
    BC_CLEAR_DEATH_NOTIFICATION = _IOW('c', 15, struct binder_handle_cookie),
    BC_DEAD_BINDER_DONE = _IOW('c', 16, binder_uintptr_t),
};

 
enum binder_driver_return_protocol {
    BR_ERROR = _IOR('r', 0, int32_t),
    BR_OK = _IO('r', 1),
    BR_TRANSACTION = _IOR('r', 2, struct binder_transaction_data),
    BR_REPLY = _IOR('r', 3, struct binder_transaction_data),
    BR_ACQUIRE_RESULT = _IOR('r', 4, int32_t),
    BR_DEAD_REPLY = _IO('r', 5),
    BR_TRANSACTION_COMPLETE = _IO('r', 6),
    BR_INCREFS = _IOR('r', 7, struct binder_ptr_cookie),
    BR_ACQUIRE = _IOR('r', 8, struct binder_ptr_cookie),
    BR_RELEASE = _IOR('r', 9, struct binder_ptr_cookie),
    BR_DECREFS = _IOR('r', 10, struct binder_ptr_cookie),
    BR_ATTEMPT_ACQUIRE = _IOR('r', 11, struct binder_ptr_cookie),
    BR_NOOP = _IO('r', 12),
    BR_SPAWN_LOOPER = _IO('r', 13),
    BR_FINISHED = _IO('r', 14),
    BR_DEAD_BINDER = _IOR('r', 15, binder_uintptr_t),
    BR_CLEAR_DEATH_NOTIFICATION_DONE = _IOR('r', 16, binder_uintptr_t),
    BR_FAILED_REPLY = _IO('r', 17),
};

#define B_PACK_CHARS(c1, c2, c3, c4) \
    ((((uint32_t)(c1)) << 24) | (((uint32_t)(c2)) << 16) | (((uint32_t)(c3)) << 8) | ((uint32_t)(c4)))
#define B_TYPE_LARGE 0x85

enum {
    BINDER_TYPE_BINDER = B_PACK_CHARS('s', 'b', '*', B_TYPE_LARGE),
    BINDER_TYPE_WEAK_BINDER = B_PACK_CHARS('w', 'b', '*', B_TYPE_LARGE),
    BINDER_TYPE_HANDLE = B_PACK_CHARS('s', 'h', '*', B_TYPE_LARGE),
    BINDER_TYPE_WEAK_HANDLE = B_PACK_CHARS('w', 'h', '*', B_TYPE_LARGE),
    BINDER_TYPE_FD = B_PACK_CHARS('f', 'd', '*', B_TYPE_LARGE),
    BINDER_TYPE_FDA = B_PACK_CHARS('f', 'd', 'a', B_TYPE_LARGE),
    BINDER_TYPE_PTR = B_PACK_CHARS('p', 't', '*', B_TYPE_LARGE),
};

#define FLAT_BINDER_FLAG_PRIORITY_MASK 0xff
#define FLAT_BINDER_FLAG_ACCEPTS_FDS   0x100

struct flat_binder_object {
    uint32_t type;
    uint32_t flags;
    union { binder_uintptr_t binder; uint32_t handle; };
    binder_uintptr_t cookie;
};

 
 
 
_Static_assert(sizeof(struct binder_write_read) == 24, "binder_write_read");
_Static_assert(sizeof(struct binder_transaction_data) == 40, "binder_transaction_data");
_Static_assert(sizeof(struct flat_binder_object) == 16, "flat_binder_object");
_Static_assert(sizeof(struct binder_ptr_cookie) == 8, "binder_ptr_cookie");
_Static_assert(sizeof(struct binder_handle_cookie) == 8, "binder_handle_cookie");

 
 
 
 
 
 

struct binder_write_read_v8 {
    uint64_t write_size;
    uint64_t write_consumed;
    uint64_t write_buffer;
    uint64_t read_size;
    uint64_t read_consumed;
    uint64_t read_buffer;
};

struct binder_transaction_data_v8 {
    union { uint32_t handle; uint64_t ptr; } target;
    uint64_t cookie;
    uint32_t code;
    uint32_t flags;
    int32_t sender_pid;
    uint32_t sender_euid;
    uint64_t data_size;
    uint64_t offsets_size;
    union {
        struct { uint64_t buffer; uint64_t offsets; } ptr;
        uint8_t buf[8];
    } data;
};

struct binder_ptr_cookie_v8 { uint64_t ptr; uint64_t cookie; };

struct binder_handle_cookie_v8 {
    uint32_t handle;
    uint64_t cookie;
} __attribute__((packed));

struct flat_binder_object_v8 {
    uint32_t type;
    uint32_t flags;
    union { uint64_t binder; uint32_t handle; };
    uint64_t cookie;
};

#define BINDER_WRITE_READ_V8 _IOWR('b', 1, struct binder_write_read_v8)

enum binder_driver_command_protocol_v8 {
    BC8_TRANSACTION = _IOW('c', 0, struct binder_transaction_data_v8),
    BC8_REPLY = _IOW('c', 1, struct binder_transaction_data_v8),
    BC8_FREE_BUFFER = _IOW('c', 3, uint64_t),
    BC8_INCREFS_DONE = _IOW('c', 8, struct binder_ptr_cookie_v8),
    BC8_ACQUIRE_DONE = _IOW('c', 9, struct binder_ptr_cookie_v8),
    BC8_ATTEMPT_ACQUIRE = _IOW('c', 10, struct binder_ptr_cookie_v8),
    BC8_REQUEST_DEATH = _IOW('c', 14, struct binder_handle_cookie_v8),
    BC8_CLEAR_DEATH = _IOW('c', 15, struct binder_handle_cookie_v8),
    BC8_DEAD_BINDER_DONE = _IOW('c', 16, uint64_t),
};

enum binder_driver_return_protocol_v8 {
    BR8_TRANSACTION = _IOR('r', 2, struct binder_transaction_data_v8),
    BR8_REPLY = _IOR('r', 3, struct binder_transaction_data_v8),
    BR8_INCREFS = _IOR('r', 7, struct binder_ptr_cookie_v8),
    BR8_ACQUIRE = _IOR('r', 8, struct binder_ptr_cookie_v8),
    BR8_RELEASE = _IOR('r', 9, struct binder_ptr_cookie_v8),
    BR8_DECREFS = _IOR('r', 10, struct binder_ptr_cookie_v8),
    BR8_ATTEMPT_ACQUIRE = _IOR('r', 11, struct binder_ptr_cookie_v8),
    BR8_DEAD_BINDER = _IOR('r', 15, uint64_t),
    BR8_CLEAR_DEATH_NOTIFICATION_DONE = _IOR('r', 16, uint64_t),
};

_Static_assert(sizeof(struct binder_write_read_v8) == 48, "binder_write_read_v8");
_Static_assert(sizeof(struct binder_transaction_data_v8) == 64, "binder_transaction_data_v8");
_Static_assert(sizeof(struct flat_binder_object_v8) == 24, "flat_binder_object_v8");
_Static_assert(sizeof(struct binder_ptr_cookie_v8) == 16, "binder_ptr_cookie_v8");
_Static_assert(sizeof(struct binder_handle_cookie_v8) == 12, "binder_handle_cookie_v8");

 
 
 
 
 
 
 
 

#define SB_MAGIC 0x73623030u    

 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define SB_MAXFDS 253

enum sb_type {
     
    SB_HELLO = 1,           
    SB_SET_CTX_MGR,         
    SB_SET_MAX_THREADS,     
    SB_TRANSACT,            
    SB_REPLY,               
    SB_REF,                 
    SB_REF_DONE,            
    SB_LOOPER,              
    SB_DEATH,               
    SB_DEAD_DONE,           
    SB_WAIT,                
    SB_UNWAIT,              
    SB_BYE,                 
     
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
    SB_ASYNC_DONE,
     
 
 
 
 
 
 
    SB_NODE_FREE,

     
    SB_R_OK = 64,
    SB_R_ERROR,             
    SB_R_TRANSACTION,       
    SB_R_REPLY,
    SB_R_COMPLETE,          
    SB_R_DEAD_REPLY,
    SB_R_FAILED_REPLY,
    SB_R_REF,               
    SB_R_SPAWN,             
    SB_R_DEAD_BINDER,       
    SB_R_CLEAR_DEATH_DONE,  
     
     
     
     
    SB_R_ACK,
};

 
 
 
 
#define SB_F_MORE 1u

 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
 
#define SB_F_MAGIC      0x4d390000u    
#define SB_F_MAGIC_MASK 0xffff0000u

struct sb_hdr {
    uint32_t len;       
    uint16_t type;
    uint16_t nfds;      
    uint32_t tid;
    uint32_t flags;     
};

struct sb_hello {
    uint32_t pid;
    uint32_t uid;
    uint32_t gid;
    uint32_t first;     
};

 
struct sb_txn {
    uint32_t handle;         
    uint32_t code;
    uint32_t flags;
    uint32_t sender_pid;
    uint32_t sender_euid;
    uint32_t pad;
    uint64_t node_ptr;       
    uint64_t node_cookie;
    uint64_t data_size;
    uint64_t offsets_size;
     
};

struct sb_ref {
    uint32_t op;             
    uint32_t handle;
};

struct sb_ptr_cookie {
    uint32_t op;             
    uint32_t pad;
    uint64_t ptr;
    uint64_t cookie;
};

struct sb_death {
    uint32_t op;             
    uint32_t handle;
    uint64_t cookie;
};

#define SB_REF_INCREFS 0
#define SB_REF_ACQUIRE 1
#define SB_REF_RELEASE 2
#define SB_REF_DECREFS 3
 
 
 
 
 
#define SB_REF_RELEASE_BUF 4
#define SB_REF_DECREFS_BUF 5

 
 
 
 
 
 
 
 
 
 

#define SB_PROP_MAGIC 0x73627072u    
#define SB_PROP_MAXREC 1024

struct sb_prop_hdr {
    uint32_t magic;
    uint32_t count;      
    uint32_t serial;     
    uint32_t max;        
};

struct sb_prop_rec {
    char k[64];
    char v[92];
};

#endif   
