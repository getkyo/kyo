#if !defined(_WIN32)

#if defined(__linux__) && !defined(_GNU_SOURCE)
#define _GNU_SOURCE
#endif

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#if defined(__APPLE__)
#include <sys/acl.h>
#include <sys/xattr.h>
#include <uuid/uuid.h>
#elif defined(__linux__)
#include <sys/vfs.h>
#include <sys/xattr.h>
#endif

struct kyo_access_attribute {
    char *name;
    unsigned char *value;
    size_t size;
    struct kyo_access_attribute *next;
};

struct kyo_durable_file {
    int fd;
    int existing;
    struct stat original;
#if defined(__APPLE__)
    filesec_t security;
#elif defined(__linux__)
    struct kyo_access_attribute *attributes;
#endif
};

static int kyo_error_kind(int error) {
    if (error == EEXIST) return 1;
    if (error == EACCES || error == EPERM) return 2;
    if (error == ENOTDIR) return 3;
    return 4;
}

#if defined(__linux__)

static void kyo_free_attributes(struct kyo_access_attribute *attribute) {
    while (attribute != NULL) {
        struct kyo_access_attribute *next = attribute->next;
        free(attribute->name);
        free(attribute->value);
        free(attribute);
        attribute = next;
    }
}

/* Only filesystem models whose access ACL is the POSIX ACL xattr are accepted.
 * NFS, CIFS, ZFS and FUSE can have ACLs that this interface cannot represent. */
static int kyo_posix_acl_filesystem_type(unsigned long type) {
    switch (type) {
        case 0xef53UL:     /* ext2, ext3, ext4 */
        case 0x01021994UL: /* tmpfs */
        case 0x858458f6UL: /* ramfs */
        case 0x58465342UL: /* XFS */
        case 0x9123683eUL: /* Btrfs */
        case 0x794c7630UL: /* overlayfs */
        case 0xf2f52010UL: /* F2FS */
            return 0;
        default:
            return ENOTSUP;
    }
}

static int kyo_posix_acl_filesystem(const char *path) {
    struct statfs fs;
    if (statfs(path, &fs) != 0) return errno;
    return kyo_posix_acl_filesystem_type((unsigned long)fs.f_type);
}

/* Integrity signatures cannot be copied onto different content. Unknown ACL
 * or security namespaces must not silently become a weaker replacement. */
static int kyo_access_attribute_kind(const char *name) {
    if (strcmp(name, "system.posix_acl_access") == 0 ||
        strcmp(name, "security.selinux") == 0 ||
        strcmp(name, "security.SMACK64") == 0 ||
        strcmp(name, "security.SMACK64EXEC") == 0 ||
        strcmp(name, "security.SMACK64MMAP") == 0 ||
        strcmp(name, "security.capability") == 0) return 1;
    if (strncmp(name, "security.", 9) == 0 ||
        strncmp(name, "system.", 7) == 0) return -1;
    return 0;
}

static int kyo_read_attributes(const char *path, int fd,
                               struct kyo_access_attribute **result) {
    ssize_t length = fd < 0 ? llistxattr(path, NULL, 0) : flistxattr(fd, NULL, 0);
    if (length < 0) return errno;
    if (length == 0) return 0;
    char *names = malloc((size_t)length);
    if (names == NULL) return ENOMEM;
    ssize_t actual = fd < 0 ? llistxattr(path, names, (size_t)length)
                            : flistxattr(fd, names, (size_t)length);
    int error = 0;
    if (actual < 0) error = errno;
    for (size_t offset = 0; error == 0 && offset < (size_t)actual;) {
        char *name = names + offset;
        size_t remaining = (size_t)actual - offset;
        size_t size = strnlen(name, remaining);
        if (size == remaining) {
            error = EIO;
            break;
        }
        offset += size + 1;
        int kind = kyo_access_attribute_kind(name);
        if (kind < 0) {
            error = ENOTSUP;
            break;
        }
        if (kind == 0) continue;
        ssize_t value_size = fd < 0 ? lgetxattr(path, name, NULL, 0)
                                    : fgetxattr(fd, name, NULL, 0);
        if (value_size < 0) {
            error = errno;
            break;
        }
        struct kyo_access_attribute *entry = calloc(1, sizeof(*entry));
        if (entry == NULL) {
            error = ENOMEM;
            break;
        }
        entry->name = strdup(name);
        entry->size = (size_t)value_size;
        entry->value = malloc(entry->size == 0 ? 1 : entry->size);
        entry->next = *result;
        *result = entry;
        if (entry->name == NULL || entry->value == NULL) {
            error = ENOMEM;
            break;
        }
        ssize_t read = fd < 0 ? lgetxattr(path, name, entry->value, entry->size)
                              : fgetxattr(fd, name, entry->value, entry->size);
        if (read < 0) error = errno;
        else if (read != value_size) error = EAGAIN;
    }
    free(names);
    return error;
}

static const struct kyo_access_attribute *kyo_find_attribute(
    const struct kyo_access_attribute *entries, const char *name) {
    for (; entries != NULL; entries = entries->next)
        if (strcmp(entries->name, name) == 0) return entries;
    return NULL;
}

static int kyo_attributes_equal(const struct kyo_access_attribute *left,
                                const struct kyo_access_attribute *right) {
    size_t left_count = 0;
    size_t right_count = 0;
    for (const struct kyo_access_attribute *entry = left; entry != NULL; entry = entry->next) {
        const struct kyo_access_attribute *other = kyo_find_attribute(right, entry->name);
        if (other == NULL || entry->size != other->size ||
            memcmp(entry->value, other->value, entry->size) != 0) return 0;
        ++left_count;
    }
    for (; right != NULL; right = right->next) ++right_count;
    return left_count == right_count;
}

static int kyo_set_attributes(struct kyo_durable_file *file, int acl) {
    struct kyo_access_attribute *current = NULL;
    int error = kyo_read_attributes(NULL, file->fd, &current);
    for (const struct kyo_access_attribute *entry = current;
         error == 0 && entry != NULL; entry = entry->next) {
        int is_acl = strcmp(entry->name, "system.posix_acl_access") == 0;
        if (is_acl == acl && kyo_find_attribute(file->attributes, entry->name) == NULL &&
            fremovexattr(file->fd, entry->name) != 0) error = errno;
    }
    for (const struct kyo_access_attribute *entry = file->attributes;
         error == 0 && entry != NULL; entry = entry->next) {
        int is_acl = strcmp(entry->name, "system.posix_acl_access") == 0;
        const struct kyo_access_attribute *old = kyo_find_attribute(current, entry->name);
        if (is_acl == acl &&
            (old == NULL || old->size != entry->size ||
             memcmp(old->value, entry->value, entry->size) != 0) &&
            fsetxattr(file->fd, entry->name, entry->value, entry->size, 0) != 0) error = errno;
    }
    kyo_free_attributes(current);
    return error;
}

#elif defined(__APPLE__)

/* These attributes impose access or execution restrictions beyond mode and
 * ACLs. Their platform-managed state cannot be assigned to different content
 * as an ordinary ACL, so refuse replacement rather than discard them. */
static int kyo_validate_darwin_attributes(const char *path) {
    const char *names[] = {"com.apple.rootless", "com.apple.macl", "com.apple.quarantine"};
    for (size_t i = 0; i < sizeof(names) / sizeof(names[0]); ++i) {
        if (getxattr(path, names[i], NULL, 0, 0, XATTR_NOFOLLOW) >= 0) return ENOTSUP;
        if (errno != ENOATTR) return errno;
    }
    return 0;
}

static int kyo_filesec_has(filesec_t security, filesec_property_t property, int *present) {
    if (filesec_query_property(security, property, present) != 0) return errno;
    return 0;
}

static int kyo_validate_filesec(filesec_t security) {
    int present = 0;
    int error = kyo_filesec_has(security, FILESEC_ACL, &present);
    if (error != 0 || !present) return error;
    acl_t acl = NULL;
    acl_flagset_t flags;
    if (filesec_get_property(security, FILESEC_ACL, &acl) != 0) return errno;
    if (acl_get_flagset_np(acl, &flags) != 0) error = errno;
    else {
        int deferred = acl_get_flag_np(flags, ACL_FLAG_DEFER_INHERIT);
        if (deferred < 0) error = errno;
        /* A deferred ACL can gain access entries when the temporary is renamed. */
        else if (deferred) error = ENOTSUP;
    }
    acl_free(acl);
    return error;
}

/* There is no vnode on which to check delete authorization before creating a
 * new target. Reject inherited deny-delete ACLs instead of creating a temporary
 * that neither rename nor failure cleanup could remove. This detects an
 * unsupported ACL shape, without evaluating principals or ACE precedence. */
static int kyo_validate_parent(const char *temporary, int existing) {
    char *parent = strdup(temporary);
    if (parent == NULL) return ENOMEM;
    char *separator = strrchr(parent, '/');
    if (separator == parent) separator[1] = '\0';
    else if (separator != NULL) *separator = '\0';
    const char *path = separator == NULL ? "." : parent;
    /* The initial private ACL does not grant delete on the temporary itself.
     * Require the directory to permit its removal even before ACL restoration. */
    int error = faccessat(AT_FDCWD, path, _RMFILE_OK, AT_EACCESS) == 0 ? 0 : errno;
    if (error != 0 || existing) {
        free(parent);
        return error;
    }
    filesec_t security = filesec_init();
    acl_t acl = NULL;
    if (security == NULL) error = ENOMEM;
    struct stat metadata;
    int present = 0;
    if (error == 0 && statx_np(path, &metadata, security) != 0) error = errno;
    if (error == 0 && !S_ISDIR(metadata.st_mode)) error = ENOTDIR;
    if (error == 0) error = kyo_filesec_has(security, FILESEC_ACL, &present);
    if (error == 0 && present && filesec_get_property(security, FILESEC_ACL, &acl) != 0) error = errno;
    if (error == 0 && acl != NULL) {
        acl_entry_t entry;
        int next = acl_get_entry(acl, ACL_FIRST_ENTRY, &entry);
        while (error == 0 && next == 0) {
            acl_tag_t tag;
            acl_permset_t permissions;
            acl_flagset_t flags;
            if (acl_get_tag_type(entry, &tag) != 0 ||
                acl_get_permset(entry, &permissions) != 0 ||
                acl_get_flagset_np(entry, &flags) != 0) error = errno;
            else {
                int denies_delete = acl_get_perm_np(permissions, ACL_DELETE);
                int inherited = acl_get_flag_np(flags, ACL_ENTRY_FILE_INHERIT);
                if (denies_delete < 0 || inherited < 0) error = errno;
                else if (tag == ACL_EXTENDED_DENY && denies_delete && inherited) error = ENOTSUP;
            }
            if (error == 0) next = acl_get_entry(acl, ACL_NEXT_ENTRY, &entry);
        }
        if (error == 0 && errno != EINVAL) error = errno == 0 ? EIO : errno;
    }
    if (acl != NULL) acl_free(acl);
    if (security != NULL) filesec_free(security);
    free(parent);
    return error;
}

static int kyo_acl_equal(acl_t left, acl_t right, int *equal) {
    ssize_t left_size = acl_size(left);
    ssize_t right_size = acl_size(right);
    if (left_size < 0 || right_size < 0) return errno;
    *equal = 0;
    if (left_size != right_size) return 0;
    void *left_data = calloc(1, (size_t)left_size);
    void *right_data = calloc(1, (size_t)right_size);
    int error = 0;
    if (left_data == NULL || right_data == NULL) error = ENOMEM;
    else if (acl_copy_ext(left_data, left, left_size) < 0 ||
             acl_copy_ext(right_data, right, right_size) < 0) error = errno;
    else *equal = memcmp(left_data, right_data, (size_t)left_size) == 0;
    free(left_data);
    free(right_data);
    return error;
}

static int kyo_verify_filesec(filesec_t original, filesec_t actual) {
    filesec_property_t properties[] = {FILESEC_UUID, FILESEC_GRPUUID, FILESEC_ACL};
    for (size_t i = 0; i < sizeof(properties) / sizeof(properties[0]); ++i) {
        int before = 0;
        int after = 0;
        int error = kyo_filesec_has(original, properties[i], &before);
        if (error == 0) error = kyo_filesec_has(actual, properties[i], &after);
        if (error != 0) return error;
        if (before != after) return ENOTSUP;
        if (!before) continue;
        if (properties[i] == FILESEC_ACL) {
            acl_t left = NULL;
            acl_t right = NULL;
            int equal = 0;
            if (filesec_get_property(original, FILESEC_ACL, &left) != 0 ||
                filesec_get_property(actual, FILESEC_ACL, &right) != 0) error = errno;
            else error = kyo_acl_equal(left, right, &equal);
            if (left != NULL) acl_free(left);
            if (right != NULL) acl_free(right);
            if (error != 0) return error;
            if (!equal) return ENOTSUP;
        } else {
            uuid_t left;
            uuid_t right;
            if (filesec_get_property(original, properties[i], &left) != 0 ||
                filesec_get_property(actual, properties[i], &right) != 0) return errno;
            if (memcmp(left, right, sizeof(left)) != 0) return ENOTSUP;
        }
    }
    return 0;
}

static int kyo_private_create(const char *path, int directory) {
    acl_t acl = acl_init(0);
    if (acl == NULL) return -1;
    filesec_t security = filesec_init();
    int error = security == NULL ? ENOMEM : 0;
    acl_flagset_t flags;
    mode_t mode = directory ? 0700 : 0000;
    int fd = -1;
    if (error == 0 &&
        (acl_get_flagset_np(acl, &flags) != 0 ||
         acl_add_flag_np(flags, ACL_FLAG_NO_INHERIT) != 0 ||
         filesec_set_property(security, FILESEC_MODE, &mode) != 0 ||
         filesec_set_property(security, FILESEC_ACL, &acl) != 0)) error = errno;
    if (error == 0) {
        fd = directory ? mkdirx_np(path, security)
                       : openx_np(path, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC | O_NOFOLLOW, security);
        if (fd < 0) error = errno;
    }
    if (security != NULL) filesec_free(security);
    acl_free(acl);
    errno = error;
    return fd;
}

#endif

static void kyo_free_durable(struct kyo_durable_file *file) {
#if defined(__APPLE__)
    if (file->security != NULL) filesec_free(file->security);
#elif defined(__linux__)
    kyo_free_attributes(file->attributes);
#endif
    free(file);
}

static int kyo_verify_private(int fd, int directory) {
    struct stat actual;
    if (fstat(fd, &actual) != 0) return errno;
    if (actual.st_uid != geteuid()) return ENOTSUP;
    if (directory) {
        if (!S_ISDIR(actual.st_mode) || (actual.st_mode & 0777) != 0700) return ENOTSUP;
    } else if (!S_ISREG(actual.st_mode) || (actual.st_mode & 07777) != 0) return ENOTSUP;
#if defined(__APPLE__)
    filesec_t security = filesec_init();
    if (security == NULL) return ENOMEM;
    int present = 0;
    int error = fstatx_np(fd, &actual, security) == 0 ? 0 : errno;
    if (error == 0) error = kyo_filesec_has(security, FILESEC_ACL, &present);
    if (error == 0 && present) {
        acl_t acl = NULL;
        if (filesec_get_property(security, FILESEC_ACL, &acl) != 0) error = errno;
        else {
            acl_entry_t entry;
            if (acl_get_entry(acl, ACL_FIRST_ENTRY, &entry) == 0) error = ENOTSUP;
            else if (errno != EINVAL) error = errno;
            acl_free(acl);
        }
    }
    filesec_free(security);
    return error;
#else
    return 0;
#endif
}

int64_t kyo_durable_open(const char *target, const char *temporary, int32_t *errors) {
    errors[0] = 0;
    errors[1] = 0;
    struct kyo_durable_file *file = calloc(1, sizeof(*file));
    int error = 0;
    if (file == NULL) error = ENOMEM;
    else {
        file->fd = -1;
#if defined(__APPLE__)
        file->security = filesec_init();
        if (file->security == NULL) error = ENOMEM;
        else if (lstatx_np(target, &file->original, file->security) == 0) file->existing = 1;
        else if (errno != ENOENT) error = errno;
#elif defined(__linux__)
        if (lstat(target, &file->original) == 0) file->existing = 1;
        else if (errno != ENOENT) error = errno;
#else
        error = ENOTSUP;
#endif
        if (error == 0 && file->existing && !S_ISREG(file->original.st_mode))
            error = S_ISDIR(file->original.st_mode) ? EISDIR : ELOOP;
#if defined(__APPLE__)
        if (error == 0 && file->existing) error = kyo_validate_filesec(file->security);
        if (error == 0) error = kyo_validate_parent(temporary, file->existing);
        if (error == 0 && file->existing) error = kyo_validate_darwin_attributes(target);
        if (error == 0 && file->existing &&
            (file->original.st_flags & (UF_IMMUTABLE | UF_APPEND | UF_DATAVAULT |
                                       SF_IMMUTABLE | SF_APPEND | SF_RESTRICTED | SF_NOUNLINK)) != 0)
            error = ENOTSUP;
        /* Darwin evaluates delete and parent delete-child ACLs together. A
         * copied deny-delete ACL must not make the temporary unremovable when
         * installing it would already be denied on the original target. */
        if (error == 0 && file->existing &&
            faccessat(AT_FDCWD, target, _DELETE_OK, AT_EACCESS | AT_SYMLINK_NOFOLLOW) != 0)
            error = errno;
        if (error == 0 && file->existing) {
            struct stat after;
            if (lstat(target, &after) != 0) error = errno;
            else if (after.st_dev != file->original.st_dev || after.st_ino != file->original.st_ino ||
                     after.st_mode != file->original.st_mode || after.st_uid != file->original.st_uid ||
                     after.st_gid != file->original.st_gid ||
                     after.st_ctimespec.tv_sec != file->original.st_ctimespec.tv_sec ||
                     after.st_ctimespec.tv_nsec != file->original.st_ctimespec.tv_nsec) error = EAGAIN;
        }
#elif defined(__linux__)
        if (error == 0 && file->existing) error = kyo_posix_acl_filesystem(target);
        if (error == 0 && file->existing) error = kyo_read_attributes(target, -1, &file->attributes);
        if (error == 0 && file->existing) {
            struct stat after;
            if (lstat(target, &after) != 0) error = errno;
            else if (after.st_dev != file->original.st_dev || after.st_ino != file->original.st_ino ||
                     after.st_mode != file->original.st_mode || after.st_uid != file->original.st_uid ||
                     after.st_gid != file->original.st_gid ||
                     after.st_ctim.tv_sec != file->original.st_ctim.tv_sec ||
                     after.st_ctim.tv_nsec != file->original.st_ctim.tv_nsec) error = EAGAIN;
        }
#endif
        if (error == 0) {
#if defined(__APPLE__)
            if (file->existing) file->fd = kyo_private_create(temporary, 0);
            else
#endif
                file->fd = open(temporary, O_CREAT | O_EXCL | O_RDWR | O_CLOEXEC | O_NOFOLLOW,
                                file->existing ? 0000 : 0666);
            if (file->fd < 0) error = errno;
            else if (file->existing) error = kyo_verify_private(file->fd, 0);
        }
    }
    if (error != 0) {
        if (file != NULL) {
            if (file->fd >= 0) {
                close(file->fd);
                unlink(temporary);
            }
            kyo_free_durable(file);
        }
        errors[0] = kyo_error_kind(error);
        errors[1] = error;
        return 0;
    }
    return (int64_t)(intptr_t)file;
}

int32_t kyo_durable_write(int64_t handle, int64_t position, const unsigned char *bytes, int32_t length) {
    struct kyo_durable_file *file = (struct kyo_durable_file *)(intptr_t)handle;
    if (file == NULL || file->fd < 0) return EBADF;
    if (position < 0 || length < 0 || position > INT64_MAX - length ||
        (int64_t)(off_t)position != position ||
        (int64_t)(off_t)(position + length) != position + length) return EINVAL;
    size_t offset = 0;
    while (offset < (size_t)length) {
        ssize_t written = pwrite(file->fd, bytes + offset, (size_t)length - offset,
                                 (off_t)(position + (int64_t)offset));
        if (written < 0) {
            if (errno == EINTR) continue;
            return errno;
        }
        if (written == 0) return EIO;
        offset += (size_t)written;
    }
    return 0;
}

int32_t kyo_durable_truncate(int64_t handle, int64_t size) {
    struct kyo_durable_file *file = (struct kyo_durable_file *)(intptr_t)handle;
    if (file == NULL || file->fd < 0) return EBADF;
    if (size < 0 || (int64_t)(off_t)size != size) return EINVAL;
    return ftruncate(file->fd, (off_t)size) == 0 ? 0 : errno;
}

int32_t kyo_durable_sync(int64_t handle) {
    struct kyo_durable_file *file = (struct kyo_durable_file *)(intptr_t)handle;
    if (file == NULL || file->fd < 0) return EBADF;
    int error = 0;
    if (file->existing) {
        struct stat actual;
        mode_t mode = file->original.st_mode & 07777;
#if defined(__APPLE__)
        filesec_t restore = filesec_dup(file->security);
        filesec_t verified = filesec_init();
        int has_acl = 0;
        if (restore == NULL || verified == NULL) error = ENOMEM;
        if (error == 0) error = kyo_filesec_has(restore, FILESEC_ACL, &has_acl);
        if (error == 0 && !has_acl &&
            filesec_set_property(restore, FILESEC_ACL, _FILESEC_REMOVE_ACL) != 0) error = errno;
        if (error == 0 && filesec_set_property(restore, FILESEC_MODE, &mode) != 0) error = errno;
        if (error == 0 && fchmodx_np(file->fd, restore) != 0) error = errno;
        if (error == 0 && fstatx_np(file->fd, &actual, verified) != 0) error = errno;
        if (error == 0) error = kyo_verify_filesec(file->security, verified);
        if (restore != NULL) filesec_free(restore);
        if (verified != NULL) filesec_free(verified);
#elif defined(__linux__)
        if (fstat(file->fd, &actual) != 0) error = errno;
        if (error == 0 && (actual.st_uid != file->original.st_uid || actual.st_gid != file->original.st_gid) &&
            fchown(file->fd, file->original.st_uid, file->original.st_gid) != 0) error = errno;
        /* Restore mandatory restrictions before the ACL can grant data access. */
        if (error == 0) error = kyo_set_attributes(file, 0);
        if (error == 0) error = kyo_set_attributes(file, 1);
        if (error == 0 && fchmod(file->fd, mode) != 0) error = errno;
        if (error == 0 && fstat(file->fd, &actual) != 0) error = errno;
        struct kyo_access_attribute *verified = NULL;
        if (error == 0) error = kyo_read_attributes(NULL, file->fd, &verified);
        if (error == 0 && !kyo_attributes_equal(file->attributes, verified)) error = ENOTSUP;
        kyo_free_attributes(verified);
#else
        error = ENOTSUP;
#endif
        if (error == 0 && ((actual.st_mode & 07777) != mode ||
                           actual.st_uid != file->original.st_uid || actual.st_gid != file->original.st_gid))
            error = ENOTSUP;
    }
    if (error == 0 && fsync(file->fd) != 0) error = errno;
    return error;
}

int32_t kyo_durable_close(int64_t handle) {
    struct kyo_durable_file *file = (struct kyo_durable_file *)(intptr_t)handle;
    if (file == NULL) return EBADF;
    int error = close(file->fd) == 0 ? 0 : errno;
    kyo_free_durable(file);
    return error;
}

#endif
