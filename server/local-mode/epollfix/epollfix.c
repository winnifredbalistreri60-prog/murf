/* LD_PRELOAD для nginx под proot на Android: seccomp приложений Android пускает только
 * epoll_create1, а glibc на x86_64/arm32 в epoll_create() зовёт старый syscall epoll_create
 * (ENOSYS). size в epoll_create с Linux 2.6.8 игнорируется, поэтому epoll_create1(0) эквивалентен. */
extern int epoll_create1(int flags);
int epoll_create(int size) { (void)size; return epoll_create1(0); }
