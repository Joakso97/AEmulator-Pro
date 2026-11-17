 
 
 
 
 
extern char __sF[];                        
extern int strerror_r(int err, char *buf, unsigned long len);    

#define EXPORT __attribute__((visibility("default")))

EXPORT void *stdin, *stdout, *stderr;
EXPORT void aemu_nativewindow_stub(void) {}
EXPORT char *__gnu_strerror_r(int err, char *buf, unsigned long len) { strerror_r(err, buf, len); return buf; }

__attribute__((constructor)) static void init(void) { stdin = __sF; stdout = __sF + 152; stderr = __sF + 304; }
