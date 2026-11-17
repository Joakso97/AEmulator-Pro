 
 
 
 
 
 
 
 
 
 
 
__attribute__((visibility("default"), used)) void aemu_hidl_check(_Bool cond, const char *msg)
    __asm__("_ZN7android8hardware7details5checkEbPKc");
void aemu_hidl_check(_Bool cond, const char *msg) { (void)msg; if (!cond) __builtin_trap(); }

__attribute__((visibility("default"), used)) void aemu_hidl_log_error(const void *message)
    __asm__("_ZN7android8hardware7details8logErrorERKNSt3__112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE");
void aemu_hidl_log_error(const void *message) { (void)message; }

__attribute__((visibility("default"), used)) int aemu_hidl_error_write_log(int tag, const char *info)
    __asm__("_ZN7android8hardware7details13errorWriteLogEiPKc");
int aemu_hidl_error_write_log(int tag, const char *info) { (void)tag; (void)info; return 0; }
