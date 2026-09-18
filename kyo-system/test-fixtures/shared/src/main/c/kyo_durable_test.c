#ifndef _DEFAULT_SOURCE
#define _DEFAULT_SOURCE
#endif
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <aclapi.h>
#include <sddl.h>
#define EXPORT __declspec(dllexport)

static int failure(DWORD error) { return -(int)(error ? error : ERROR_INVALID_DATA); }

static WCHAR *wide_path(const char *path) {
    int size = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, NULL, 0);
    if (size == 0) return NULL;
    WCHAR *wide = (WCHAR *)malloc((size_t)size * sizeof(WCHAR));
    if (!wide) { SetLastError(ERROR_NOT_ENOUGH_MEMORY); return NULL; }
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, path, -1, wide, size)) {
        DWORD error = GetLastError(); free(wide); SetLastError(error); return NULL;
    }
    return wide;
}

static TOKEN_USER *current_user(void) {
    HANDLE token;
    if (!OpenThreadToken(GetCurrentThread(), TOKEN_QUERY, TRUE, &token)) {
        if (GetLastError() != ERROR_NO_TOKEN || !OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token)) return NULL;
    }
    DWORD size = 0;
    GetTokenInformation(token, TokenUser, NULL, 0, &size);
    TOKEN_USER *user = (TOKEN_USER *)malloc(size);
    if (!user) { CloseHandle(token); SetLastError(ERROR_NOT_ENOUGH_MEMORY); return NULL; }
    if (!GetTokenInformation(token, TokenUser, user, size, &size)) {
        DWORD error = GetLastError(); free(user); CloseHandle(token); SetLastError(error); return NULL;
    }
    CloseHandle(token);
    return user;
}

/* Keep the Scala fixture library independent of ntdll linkage. This setter is
   used only to construct legacy ACLs for the preservation regression tests. */
static DWORD legacy_dacl(const WCHAR *path) {
    typedef LONG (NTAPI *set_security_fn)(HANDLE, SECURITY_INFORMATION,
                                         PSECURITY_DESCRIPTOR);
    set_security_fn set = (set_security_fn)(void *)GetProcAddress(
        GetModuleHandleW(L"ntdll.dll"), "NtSetSecurityObject");
    HANDLE file;
    PSECURITY_DESCRIPTOR descriptor = NULL;
    SECURITY_DESCRIPTOR_CONTROL control;
    DWORD revision, error;
    if (set == NULL)
        return ERROR_PROC_NOT_FOUND;
    file = CreateFileW(path, READ_CONTROL | WRITE_DAC,
                       FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                       NULL, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS, NULL);
    if (file == INVALID_HANDLE_VALUE)
        return GetLastError();
    error = GetSecurityInfo(file, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                            NULL, NULL, NULL, NULL, &descriptor);
    if (error == ERROR_SUCCESS &&
        set(file, DACL_SECURITY_INFORMATION, descriptor) < 0)
        error = ERROR_NOT_SUPPORTED;
    if (descriptor != NULL)
        LocalFree(descriptor);
    descriptor = NULL;
    if (error == ERROR_SUCCESS)
        error = GetSecurityInfo(file, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                                NULL, NULL, NULL, NULL, &descriptor);
    if (error == ERROR_SUCCESS) {
        if (!GetSecurityDescriptorControl(descriptor, &control, &revision))
            error = GetLastError();
        else if ((control & (SE_DACL_PRESENT | SE_DACL_PROTECTED |
                             SE_DACL_AUTO_INHERITED | SE_DACL_AUTO_INHERIT_REQ)) !=
                 (SE_DACL_PRESENT | SE_DACL_PROTECTED))
            error = ERROR_INVALID_SECURITY_DESCR;
    }
    if (descriptor != NULL)
        LocalFree(descriptor);
    if (!CloseHandle(file) && error == ERROR_SUCCESS)
        error = GetLastError();
    return error;
}

/* A low mandatory label adds an access restriction independently of the DACL. */
static DWORD low_integrity_label(const WCHAR *path) {
    PSECURITY_DESCRIPTOR descriptor = NULL, actual = NULL;
    PACL sacl = NULL, installed = NULL;
    BOOL present, defaulted;
    DWORD error = ERROR_SUCCESS;
    void *expected_ace, *actual_ace;
    if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
            L"S:(ML;;NW;;;LW)", SDDL_REVISION_1, &descriptor, NULL))
        return GetLastError();
    if (!GetSecurityDescriptorSacl(descriptor, &present, &sacl, &defaulted)) {
        error = GetLastError();
        goto done;
    }
    error = SetNamedSecurityInfoW((LPWSTR)path, SE_FILE_OBJECT,
                                  LABEL_SECURITY_INFORMATION,
                                  NULL, NULL, NULL, sacl);
    if (error != ERROR_SUCCESS)
        goto done;
    error = GetNamedSecurityInfoW((LPWSTR)path, SE_FILE_OBJECT,
                                  LABEL_SECURITY_INFORMATION,
                                  NULL, NULL, NULL, &installed, &actual);
    if (error != ERROR_SUCCESS)
        goto done;
    if (installed == NULL || installed->AceCount != 1 ||
        !GetAce(sacl, 0, &expected_ace) || !GetAce(installed, 0, &actual_ace) ||
        ((ACE_HEADER *)expected_ace)->AceSize != ((ACE_HEADER *)actual_ace)->AceSize ||
        memcmp(expected_ace, actual_ace, ((ACE_HEADER *)expected_ace)->AceSize) != 0)
        error = ERROR_INVALID_SECURITY_DESCR;
done:
    if (actual != NULL)
        LocalFree(actual);
    LocalFree(descriptor);
    return error;
}

EXPORT int kyo_permissions_test_configure(const char *path, int kind) {
    if (kind == 15 || kind == 16) {
        if (kind == 16) {
            int configured = kyo_permissions_test_configure(path, 0);
            if (configured != 0)
                return configured;
        }
        WCHAR *label_path = wide_path(path);
        if (label_path == NULL)
            return failure(GetLastError());
        DWORD error = low_integrity_label(label_path);
        free(label_path);
        return error == ERROR_SUCCESS ? 0 : failure(error);
    }
    int legacy = kind >= 11 && kind <= 14;
    if (legacy) {
        const int kinds[] = {0, 1, 3, 4};
        kind = kinds[kind - 11];
    }
    WCHAR *wide = wide_path(path);
    if (!wide) return failure(GetLastError());
    TOKEN_USER *user = current_user();
    if (!user) { DWORD error = GetLastError(); free(wide); return failure(error); }
    unsigned char everyone[SECURITY_MAX_SID_SIZE];
    DWORD everyone_size = sizeof(everyone);
    unsigned char storage[1024];
    PACL acl = (PACL)storage;
    DWORD allowed = kind == 5 ? FILE_ADD_FILE | FILE_LIST_DIRECTORY | FILE_TRAVERSE | FILE_READ_ATTRIBUTES | READ_CONTROL | WRITE_DAC | SYNCHRONIZE :
                    kind == 6 ? FILE_GENERIC_READ | READ_CONTROL | WRITE_DAC : FILE_ALL_ACCESS;
    DWORD error = ERROR_SUCCESS;
    if (!CreateWellKnownSid(WinWorldSid, NULL, everyone, &everyone_size) || !InitializeAcl(acl, sizeof(storage), ACL_REVISION)) {
        error = GetLastError();
    } else if (kind != 3 && kind != 4 && !AddAccessAllowedAceEx(acl, ACL_REVISION, 0, allowed, user->User.Sid)) {
        error = GetLastError();
    } else if (kind == 1 && !AddAccessDeniedAceEx(acl, ACL_REVISION, 0, FILE_READ_DATA, everyone)) {
        error = GetLastError();
    } else if (kind == 2 && !AddAccessAllowedAceEx(acl, ACL_REVISION, OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE, FILE_GENERIC_READ, everyone)) {
        error = GetLastError();
    } else {
        error = SetNamedSecurityInfoW(wide, SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
            NULL, NULL, kind == 4 ? NULL : acl, NULL);
    }
    if (error == ERROR_SUCCESS && legacy)
        error = legacy_dacl(wide);
    free(user);
    free(wide);
    return error == ERROR_SUCCESS ? 0 : failure(error);
}

EXPORT int kyo_permissions_test_snapshot(const char *path, unsigned char *out, int capacity) {
    WCHAR *wide = wide_path(path);
    if (!wide) return failure(GetLastError());
    PSECURITY_DESCRIPTOR descriptor = NULL;
    SECURITY_INFORMATION info = OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION | DACL_SECURITY_INFORMATION | LABEL_SECURITY_INFORMATION;
    DWORD error = GetNamedSecurityInfoW(wide, SE_FILE_OBJECT, info, NULL, NULL, NULL, NULL, &descriptor);
    free(wide);
    if (error != ERROR_SUCCESS) return failure(error);
    LPWSTR text = NULL;
    SECURITY_DESCRIPTOR_CONTROL control;
    DWORD revision;
    if (!GetSecurityDescriptorControl(descriptor, &control, &revision) ||
        !ConvertSecurityDescriptorToStringSecurityDescriptorW(descriptor, SDDL_REVISION_1, info, &text, NULL)) {
        error = GetLastError(); LocalFree(descriptor); return failure(error);
    }
    int prefix = snprintf((char *)out, (size_t)capacity, "%u:", (unsigned)(control & (SE_DACL_PROTECTED | SE_DACL_AUTO_INHERITED | SE_SACL_PROTECTED | SE_SACL_AUTO_INHERITED)));
    int needed = WideCharToMultiByte(CP_UTF8, 0, text, -1, NULL, 0, NULL, NULL);
    int result;
    if (prefix < 0 || needed <= 0 || prefix + needed > capacity) result = failure(ERROR_INSUFFICIENT_BUFFER);
    else if (!WideCharToMultiByte(CP_UTF8, 0, text, -1, (char *)out + prefix, capacity - prefix, NULL, NULL)) result = failure(GetLastError());
    else result = prefix + needed - 1;
    LocalFree(text);
    LocalFree(descriptor);
    return result;
}

EXPORT int kyo_permissions_test_private_access(const char *path) {
    WCHAR *wide = wide_path(path);
    if (!wide) return failure(GetLastError());
    PSECURITY_DESCRIPTOR descriptor = NULL;
    PACL acl = NULL;
    DWORD error = GetNamedSecurityInfoW(wide, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION, NULL, NULL, &acl, NULL, &descriptor);
    free(wide);
    if (error != ERROR_SUCCESS) return failure(error);
    TOKEN_USER *user = current_user();
    if (!user) { error = GetLastError(); LocalFree(descriptor); return failure(error); }
    SECURITY_DESCRIPTOR_CONTROL control;
    DWORD revision;
    int result = 1;
    if (!GetSecurityDescriptorControl(descriptor, &control, &revision)) result = failure(GetLastError());
    else if (!acl || !(control & SE_DACL_PROTECTED)) result = 0;
    else {
        for (DWORD i = 0; i < acl->AceCount && result == 1; i++) {
            void *entry;
            if (!GetAce(acl, i, &entry)) { result = failure(GetLastError()); break; }
            ACE_HEADER *header = (ACE_HEADER *)entry;
            if (header->AceType == ACCESS_ALLOWED_ACE_TYPE) {
                ACCESS_ALLOWED_ACE *allow = (ACCESS_ALLOWED_ACE *)entry;
                if (!EqualSid(&allow->SidStart, user->User.Sid)) result = 0;
            } else if (header->AceType != ACCESS_DENIED_ACE_TYPE) result = 0;
        }
    }
    free(user);
    LocalFree(descriptor);
    return result;
}

#else
#include <sys/stat.h>
#include <unistd.h>
#define EXPORT
static int failure(int error) { return -(error ? error : EIO); }

#ifdef __APPLE__
#include <sys/acl.h>
#include <sys/xattr.h>
#include <fcntl.h>
#include <grp.h>
#include <pwd.h>
#include <membership.h>

EXPORT int kyo_permissions_test_configure(const char *path, int kind) {
    if (kind < 0 || (kind > 2 && (kind < 7 || kind > 10))) return failure(EINVAL);
    acl_t acl = acl_init(kind == 0 ? 0 : 1);
    if (!acl) return failure(errno);
    if (kind != 0) {
        struct group *everyone = getgrnam("everyone");
        uuid_t uuid;
        acl_entry_t entry;
        acl_permset_t permissions;
        acl_flagset_t flags;
        int deny = kind == 1 || kind >= 7;
        acl_perm_t permission = kind == 7 || kind == 8 ? ACL_DELETE :
                                kind == 9 ? ACL_DELETE_CHILD : kind == 1 ? ACL_WRITE_DATA : ACL_READ_DATA;
        int error;
        if (kind == 10) {
            struct passwd *nobody = getpwnam("nobody");
            error = nobody ? mbr_uid_to_uuid(nobody->pw_uid, uuid) : ENOENT;
        } else error = everyone ? mbr_gid_to_uuid(everyone->gr_gid, uuid) : ENOENT;
        if (error) { acl_free(acl); return failure(error); }
        if (acl_create_entry(&acl, &entry) < 0 ||
            acl_set_tag_type(entry, deny ? ACL_EXTENDED_DENY : ACL_EXTENDED_ALLOW) < 0 ||
            acl_set_qualifier(entry, uuid) < 0 ||
            acl_get_permset(entry, &permissions) < 0 ||
            acl_add_perm(permissions, permission) < 0 ||
            (kind <= 2 && acl_add_perm(permissions, kind == 1 ? ACL_APPEND_DATA : ACL_EXECUTE) < 0)) {
            error = errno; acl_free(acl); return failure(error);
        }
        if ((kind == 2 || kind == 8 || kind == 10) && (acl_get_flagset_np(entry, &flags) < 0 ||
            acl_add_flag_np(flags, ACL_ENTRY_FILE_INHERIT) < 0 ||
            ((kind == 2 || kind == 10) && acl_add_flag_np(flags, ACL_ENTRY_DIRECTORY_INHERIT) < 0))) {
            error = errno; acl_free(acl); return failure(error);
        }
    }
    int result = acl_set_file(path, ACL_TYPE_EXTENDED, acl);
    int error = errno;
    acl_free(acl);
    if (result < 0) return failure(error);
    if (kind == 0 || kind == 1 || kind == 7) {
        struct stat st;
        if (stat(path, &st) < 0 || chmod(path, S_ISDIR(st.st_mode) ? 0700 : 0600) < 0) return failure(errno);
    }
    return 0;
}

EXPORT int kyo_permissions_test_snapshot(const char *path, unsigned char *out, int capacity) {
    struct stat st;
    filesec_t sec = filesec_init();
    if (!sec) return failure(errno);
    if (statx_np(path, &st, sec) < 0) { int error = errno; filesec_free(sec); return failure(error); }
    int prefix = snprintf((char *)out, (size_t)capacity, "%o:%u:%u:", (unsigned)(st.st_mode & 07777), st.st_uid, st.st_gid);
    acl_t acl = NULL;
    int has_acl = filesec_get_property(sec, FILESEC_ACL, &acl);
    int error = errno;
    filesec_free(sec);
    if (prefix < 0 || prefix >= capacity) { if (acl) acl_free(acl); return failure(ERANGE); }
    if (has_acl < 0) return error == ENOENT ? prefix : failure(error);
    ssize_t length = acl_copy_ext(out + prefix, acl, capacity - prefix);
    error = errno;
    acl_free(acl);
    if (length < 0) return failure(error);
    return prefix + (int)length;
}

EXPORT int kyo_permissions_test_private_access(const char *path) {
    struct stat st;
    if (stat(path, &st) < 0) return failure(errno);
    if (st.st_mode & 0077) return 0;
    acl_t acl = acl_get_file(path, ACL_TYPE_EXTENDED);
    if (!acl) return errno == ENOENT ? 1 : failure(errno);
    acl_entry_t entry;
    int result = acl_get_entry(acl, ACL_FIRST_ENTRY, &entry);
    int error = errno;
    acl_free(acl);
    return result == 0 ? 0 : error == EINVAL ? 1 : failure(error);
}

#elif defined(__linux__)
#include <sys/xattr.h>
#include <endian.h>

EXPORT int kyo_permissions_test_configure(const char *path, int kind) {
    if (kind == 0) {
        if (removexattr(path, "system.posix_acl_access") < 0 && errno != ENODATA) return failure(errno);
        return chmod(path, 0600) < 0 ? failure(errno) : 0;
    }
    if (kind != 1 && kind != 2 && kind != 10) return failure(EINVAL);
    struct entry { uint16_t tag, permissions; uint32_t id; };
    struct { uint32_t version; struct entry entries[5]; } acl;
    acl.version = htole32(2);
    const uint16_t tags[5] = {1, 2, 4, 16, 32};
    const uint16_t permissions[5] = {6, 4, 0, 4, 0};
    for (int i = 0; i < 5; i++) {
        acl.entries[i].tag = htole16(tags[i]);
        acl.entries[i].permissions = htole16(kind == 10 && i != 0 ? 0 : permissions[i]);
        acl.entries[i].id = htole32(i == 1 ? 65534 : UINT32_MAX);
    }
    return setxattr(path, kind == 2 || kind == 10 ? "system.posix_acl_default" : "system.posix_acl_access", &acl, sizeof(acl), 0) < 0 ? failure(errno) : 0;
}

EXPORT int kyo_permissions_test_snapshot(const char *path, unsigned char *out, int capacity) {
    struct stat st;
    if (stat(path, &st) < 0) return failure(errno);
    int prefix = snprintf((char *)out, (size_t)capacity, "%o:%u:%u:", (unsigned)(st.st_mode & 07777), st.st_uid, st.st_gid);
    if (prefix < 0 || prefix >= capacity) return failure(ERANGE);
    ssize_t length = getxattr(path, "system.posix_acl_access", out + prefix, (size_t)(capacity - prefix));
    if (length < 0) return errno == ENODATA ? prefix : failure(errno);
    return prefix + (int)length;
}

EXPORT int kyo_permissions_test_private_access(const char *path) {
    struct stat st;
    if (stat(path, &st) < 0) return failure(errno);
    return (st.st_mode & 0077) == 0;
}

#else
EXPORT int kyo_permissions_test_configure(const char *path, int kind) { (void)path; (void)kind; return failure(ENOTSUP); }
EXPORT int kyo_permissions_test_snapshot(const char *path, unsigned char *out, int capacity) { (void)path; (void)out; (void)capacity; return failure(ENOTSUP); }
EXPORT int kyo_permissions_test_private_access(const char *path) { (void)path; return failure(ENOTSUP); }
#endif
#endif

EXPORT int kyo_permissions_test_can_inspect_security(const char *path) {
#ifdef __APPLE__
    /* Query an unrelated absent attribute to test the host's metadata read authorization. */
    if (getxattr(path, "com.kyo.permission-test-probe", NULL, 0, 0, XATTR_NOFOLLOW) >= 0 || errno == ENOATTR) return 1;
    return errno == EACCES || errno == EPERM ? 0 : failure(errno);
#elif defined(__linux__)
    if (llistxattr(path, NULL, 0) >= 0) return 1;
    return errno == EACCES || errno == EPERM ? 0 : failure(errno);
#else
    (void)path;
    return 1;
#endif
}
