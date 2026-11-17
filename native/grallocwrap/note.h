 
 
struct rgb_note { uint32_t magic; int32_t w, h, stride, format; uint32_t pad[3]; };
#define RGB_NOTE 0x52474541u
struct rgb_note_handle { int version, num_fds, num_ints, fd, magic, flags, size, offset; uint64_t base; };
 
 
 
static inline const struct rgb_note_handle *note_handle_of(const void *handle) {
    const struct rgb_note_handle *p = handle;
    if (!p || p->version != 12 || p->num_fds != 1 || p->num_ints < 6 || p->num_ints > 16 || p->magic != 0x3141592) return 0;
    return p;
}
static inline struct rgb_note *rgb_note_at(const void *handle) {
    const struct rgb_note_handle *p = note_handle_of(handle);
    if (!p || !p->base || p->size < 4096) return 0;
    return (struct rgb_note *)(p->base + (uint64_t)p->size - sizeof(struct rgb_note));
}
static inline const struct rgb_note *rgb_note_of(const void *handle) {
    const struct rgb_note *n = rgb_note_at(handle);
    return n && n->magic == RGB_NOTE && n->w > 0 && n->h > 0 && n->stride >= n->w ? n : 0;
}

 
 
 
 
struct meta_note { uint32_t magic; int32_t w, h, format, stride, layers, dataspace, blend; uint64_t usage, id; char name[64]; uint64_t pad[2]; };
#define META_NOTE 0x4d454541u
#define META_ROOM (32 + sizeof(struct meta_note))
static inline struct meta_note *meta_note_at(const void *handle) {
    const struct rgb_note_handle *p = note_handle_of(handle);
    if (!p || !p->base || p->size < 4096) return 0;
    return (struct meta_note *)(p->base + (uint64_t)p->size - META_ROOM);
}
