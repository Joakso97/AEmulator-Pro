 
 
#ifndef AEMU_GLBRIDGE_COMMON_H
#define AEMU_GLBRIDGE_COMMON_H
#include <stdint.h>

#define AEMU_GL_PROTOCOL 1
#define AEMU_GL_SOCKET "/dev/socket/aemu_gl"
#define NIL 0xffffffffu

 
enum {
    OP_HELLO = 0x4000,       
    OP_CTX_CREATE,           
    OP_CTX_DESTROY,          
    OP_MAKE_CURRENT,         
    OP_RELEASE,              
    OP_SURF_SIZE,            
    OP_SURF_DESTROY,         
    OP_SWAP,                 
    OP_DRAW_CLIENT,          
    OP_MAP_READ,             
    OP_MAP_WRITE,            
    OP_GET_UNIFORM,          
    OP_GET_STRING,           
    OP_IMAGE_TEX,            
    OP_PING,                 
    OP_IMAGE_BIND,           
    OP_IMAGE_CREATE,         
    OP_IMAGE_TARGET,         
    OP_IMAGE_DESTROY,        
    OP_SWAP_BUF,             
    OP_BUF_READ,             
 
    OP_IMAGE_DATA,           
    OP_PRESENT,              
 
};

 
static inline uint32_t aemu_get_count0(uint32_t pname, uint32_t *count_name) {
    switch (pname) {
    case 0x0BA2:   case 0x0C10:   case 0x0C22:  
    case 0x0C23:   case 0x8005:  
        return 4;
    case 0x0B70:   case 0x846D:   case 0x846E:  
    case 0x0D3A:  
        return 2;
    case 0x86A3: *count_name = 0x86A2; return 0;     
    case 0x87FF: *count_name = 0x87FE; return 0;     
    case 0x8DF8: *count_name = 0x8DF9; return 0;     
    }
    return 1;
}
#endif
