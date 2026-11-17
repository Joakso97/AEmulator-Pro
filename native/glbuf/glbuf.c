 
 
 
 
 
 
 
 
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <poll.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))
#define MAXFD 1024
#define CHUNK (256 * 1024)

struct buf { char *data; size_t pos, len; };
static struct buf *g_buf[MAXFD];           
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

static ssize_t raw_read(int fd, void *b, size_t n) { return syscall(SYS_read, fd, b, n); }

EXPORT int accept(int s, struct sockaddr *a, socklen_t *l) {
    int fd = (int)syscall(SYS_accept4, s, a, l, 0);
    if (fd >= 0 && fd < MAXFD) {
        struct buf *b = calloc(1, sizeof *b);
        if (b && (b->data = malloc(CHUNK))) {
            pthread_mutex_lock(&g_lock);
            free(g_buf[fd] ? g_buf[fd]->data : 0); free(g_buf[fd]);
            g_buf[fd] = b;
            pthread_mutex_unlock(&g_lock);
        } else free(b);
    }
    return fd;
}

static struct buf *buf_of(int fd) {
    if (fd < 0 || fd >= MAXFD) return 0;
    return __atomic_load_n(&g_buf[fd], __ATOMIC_ACQUIRE);
}

 
EXPORT ssize_t read(int fd, void *dst, size_t n) {
    struct buf *b = buf_of(fd);
    if (!b) return raw_read(fd, dst, n);
    if (b->pos == b->len) {
        if (n >= CHUNK) return raw_read(fd, dst, n);
        ssize_t r = raw_read(fd, b->data, CHUNK);
        if (r <= 0) return r;
        b->pos = 0; b->len = (size_t)r;
    }
    size_t k = b->len - b->pos;
    if (k > n) k = n;
    memcpy(dst, b->data + b->pos, k);
    b->pos += k;
    return (ssize_t)k;
}

EXPORT int poll(struct pollfd *fds, nfds_t n, int timeout) {
    int ready = 0;
    for (nfds_t i = 0; i < n; i++) {
        struct buf *b = buf_of(fds[i].fd);
        if (b && b->pos < b->len && (fds[i].events & POLLIN)) ready++;
    }
    struct timespec ts, *tp = 0;
    if (ready || timeout >= 0) { int t = ready ? 0 : timeout; ts.tv_sec = t / 1000; ts.tv_nsec = (t % 1000) * 1000000L; tp = &ts; }
    int r = (int)syscall(SYS_ppoll, fds, n, tp, 0, 0);
    if (!ready) return r;
    if (r < 0) { if (errno != EINTR) return r; for (nfds_t i = 0; i < n; i++) fds[i].revents = 0; r = 0; }
    for (nfds_t i = 0; i < n; i++) {
        struct buf *b = buf_of(fds[i].fd);
        if (b && b->pos < b->len && (fds[i].events & POLLIN)) {
            if (!fds[i].revents) r++;
            fds[i].revents |= POLLIN;
        }
    }
    return r;
}

EXPORT int close(int fd) {
    if (fd >= 0 && fd < MAXFD && g_buf[fd]) {
        pthread_mutex_lock(&g_lock);
        struct buf *b = g_buf[fd];
        __atomic_store_n(&g_buf[fd], 0, __ATOMIC_RELEASE);
        pthread_mutex_unlock(&g_lock);
        if (b) { free(b->data); free(b); }
    }
    return (int)syscall(SYS_close, fd);
}
