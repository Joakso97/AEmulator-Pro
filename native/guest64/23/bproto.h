 
 
#ifndef AEMU_BPROTO_H
#define AEMU_BPROTO_H

#define OP_HELLO 1       
#define OP_BCS 2         
#define OP_TXN 3         
#define OP_READ 4        

#define F_READ 1         
#define F_NONBLOCK 2     
#define F_BIG 4          
#define F_TXN 8          
#define F_AGAIN 16       
#define F_WAKE 32        

#define INLINE_MAX 49152

 
#define BC_AEMU_FREE 0x4008633cu        

struct c_hdr { unsigned op, flags, read_size, euid; };
struct c_hello { struct c_hdr h; unsigned pid, tid, main, pad; };
 
 
struct c_txn { struct c_hdr h; unsigned cmd, handle, code, tflags; unsigned long data_size, offsets_size, extra_size; };

struct d_hdr {
    unsigned br_size, flags;
    unsigned cmd, code, tflags, sender_euid; int sender_pid; unsigned nfds;
    unsigned long ptr, cookie, data_size, offsets_size, id, extra_size;
};

#endif
