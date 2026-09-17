#ifdef _WIN32

#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0602
#endif
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winternl.h>
#include <aclapi.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <wchar.h>

#define KYO_DURABLE_EXPORT __declspec(dllexport)

/* The user-mode form of ZwSetSecurityObject is exported by ntdll. */
NTSYSAPI NTSTATUS NTAPI NtSetSecurityObject(
    HANDLE handle, SECURITY_INFORMATION information,
    PSECURITY_DESCRIPTOR descriptor);

/* These ACEs also restrict access, although Windows stores them in the SACL. */
#define KYO_ACCESS_SACL_INFORMATION                                             \
    (LABEL_SECURITY_INFORMATION | ATTRIBUTE_SECURITY_INFORMATION |             \
     SCOPE_SECURITY_INFORMATION | PROCESS_TRUST_LABEL_SECURITY_INFORMATION |   \
     ACCESS_FILTER_SECURITY_INFORMATION)
#define KYO_SECURITY_INFORMATION                                               \
    (OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION |                 \
     DACL_SECURITY_INFORMATION | KYO_ACCESS_SACL_INFORMATION)
#define KYO_DACL_CONTROL                                                       \
    (SE_DACL_PRESENT | SE_DACL_DEFAULTED | SE_DACL_PROTECTED |                   \
     SE_DACL_AUTO_INHERITED | SE_DACL_AUTO_INHERIT_REQ)
#define KYO_SACL_CONTROL                                                       \
    (SE_SACL_PRESENT | SE_SACL_DEFAULTED | SE_SACL_PROTECTED |                   \
     SE_SACL_AUTO_INHERITED | SE_SACL_AUTO_INHERIT_REQ)
#define KYO_OWNER_CONTROL (SE_OWNER_DEFAULTED | SE_GROUP_DEFAULTED)

typedef struct {
    PSECURITY_DESCRIPTOR descriptor;
    PSID owner;
    PSID group;
    PACL dacl;
    PACL sacl;
    SECURITY_DESCRIPTOR_CONTROL control;
} kyo_windows_security;

typedef struct {
    HANDLE file;
    kyo_windows_security security;
    PACL cleanup_acl;
    DWORD cleanup_error;
} kyo_windows_durable;

static void kyo_windows_security_free(kyo_windows_security *security) {
    if (security->descriptor != NULL)
        LocalFree(security->descriptor);
    memset(security, 0, sizeof(*security));
}

static DWORD kyo_windows_security_read(HANDLE file,
                                      kyo_windows_security *security) {
    DWORD revision;
    BOOL present, defaulted;
    DWORD error = GetSecurityInfo(file, SE_FILE_OBJECT,
                                 KYO_SECURITY_INFORMATION, NULL, NULL, NULL,
                                 NULL, &security->descriptor);
    if (error != ERROR_SUCCESS)
        goto failed;
    if (!IsValidSecurityDescriptor(security->descriptor)) {
        error = ERROR_INVALID_SECURITY_DESCR;
        goto failed;
    }
    if (!GetSecurityDescriptorControl(security->descriptor,
                                      &security->control, &revision) ||
        !GetSecurityDescriptorOwner(security->descriptor, &security->owner,
                                    &defaulted) ||
        !GetSecurityDescriptorGroup(security->descriptor, &security->group,
                                    &defaulted) ||
        !GetSecurityDescriptorDacl(security->descriptor, &present,
                                   &security->dacl, &defaulted) ||
        !GetSecurityDescriptorSacl(security->descriptor, &present,
                                   &security->sacl, &defaulted)) {
        error = GetLastError();
        goto failed;
    }
    if (security->owner == NULL || security->group == NULL ||
        !IsValidSid(security->owner) || !IsValidSid(security->group) ||
        (security->dacl != NULL && !IsValidAcl(security->dacl)) ||
        (security->sacl != NULL && !IsValidAcl(security->sacl))) {
        error = ERROR_INVALID_SECURITY_DESCR;
        goto failed;
    }
    return ERROR_SUCCESS;
failed:
    kyo_windows_security_free(security);
    return error;
}

/* ACL capacity and unused trailing bytes do not affect access. ACE order does. */
static int kyo_windows_acl_equal(PACL left, PACL right) {
    DWORD i;
    if (left == NULL || right == NULL)
        return left == right;
    if (left->AclRevision != right->AclRevision ||
        left->AceCount != right->AceCount)
        return 0;
    for (i = 0; i < left->AceCount; ++i) {
        ACE_HEADER *a, *b;
        if (!GetAce(left, i, (void **)&a) ||
            !GetAce(right, i, (void **)&b) || a->AceSize != b->AceSize ||
            memcmp(a, b, a->AceSize) != 0)
            return 0;
    }
    return 1;
}

static int kyo_windows_security_equal(const kyo_windows_security *left,
                                     const kyo_windows_security *right) {
    return EqualSid(left->owner, right->owner) &&
           EqualSid(left->group, right->group) &&
           ((left->control ^ right->control) &
            (KYO_OWNER_CONTROL | KYO_DACL_CONTROL | KYO_SACL_CONTROL)) == 0 &&
           kyo_windows_acl_equal(left->dacl, right->dacl) &&
           kyo_windows_acl_equal(left->sacl, right->sacl);
}

static int kyo_windows_acl_type_equal(PACL left, PACL right, BYTE type) {
    DWORD i = 0, j = 0;
    for (;;) {
        ACE_HEADER *a = NULL, *b = NULL;
        while (left != NULL && i < left->AceCount) {
            if (!GetAce(left, i++, (void **)&a))
                return 0;
            if (a->AceType == type)
                break;
            a = NULL;
        }
        while (right != NULL && j < right->AceCount) {
            if (!GetAce(right, j++, (void **)&b))
                return 0;
            if (b->AceType == type)
                break;
            b = NULL;
        }
        if (a == NULL || b == NULL)
            return a == b;
        if (a->AceSize != b->AceSize || memcmp(a, b, a->AceSize) != 0)
            return 0;
    }
}

/* Query the effective token without enabling privileges or changing identity. */
static DWORD kyo_windows_security_privilege(BOOL *enabled) {
    HANDLE token;
    TOKEN_PRIVILEGES *privileges = NULL;
    LUID security;
    DWORD size = 0, error = ERROR_SUCCESS, i;
    *enabled = FALSE;
    if (!OpenThreadToken(GetCurrentThread(), TOKEN_QUERY, TRUE, &token)) {
        error = GetLastError();
        if (error != ERROR_NO_TOKEN)
            return error;
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token))
            return GetLastError();
    }
    if (!LookupPrivilegeValueW(NULL, L"SeSecurityPrivilege", &security)) {
        error = GetLastError();
        goto done;
    }
    if (GetTokenInformation(token, TokenPrivileges, NULL, 0, &size)) {
        error = ERROR_INVALID_DATA;
        goto done;
    }
    error = GetLastError();
    if (error != ERROR_INSUFFICIENT_BUFFER)
        goto done;
    privileges = (TOKEN_PRIVILEGES *)malloc(size);
    if (privileges == NULL) {
        error = ERROR_NOT_ENOUGH_MEMORY;
        goto done;
    }
    if (!GetTokenInformation(token, TokenPrivileges, privileges, size, &size)) {
        error = GetLastError();
        goto done;
    }
    for (i = 0; i < privileges->PrivilegeCount; ++i) {
        LUID_AND_ATTRIBUTES *value = &privileges->Privileges[i];
        if (value->Luid.LowPart == security.LowPart &&
            value->Luid.HighPart == security.HighPart &&
            (value->Attributes & SE_PRIVILEGE_ENABLED) != 0) {
            *enabled = TRUE;
            break;
        }
    }
    error = ERROR_SUCCESS;
done:
    free(privileges);
    if (!CloseHandle(token) && error == ERROR_SUCCESS)
        error = GetLastError();
    return error;
}

static int kyo_windows_acl_type_changed(PACL expected, PACL current,
                                         BYTE type, int control_changed) {
    return !kyo_windows_acl_type_equal(expected, current, type) ||
           (control_changed &&
            (!kyo_windows_acl_type_equal(expected, NULL, type) ||
             !kyo_windows_acl_type_equal(current, NULL, type)));
}

static SECURITY_INFORMATION kyo_windows_security_changes(
    const kyo_windows_security *expected,
    const kyo_windows_security *current) {
    SECURITY_INFORMATION information = 0;
    SECURITY_DESCRIPTOR_CONTROL control = expected->control ^ current->control;
    if (!EqualSid(expected->owner, current->owner) ||
        (control & SE_OWNER_DEFAULTED) != 0)
        information |= OWNER_SECURITY_INFORMATION;
    if (!EqualSid(expected->group, current->group) ||
        (control & SE_GROUP_DEFAULTED) != 0)
        information |= GROUP_SECURITY_INFORMATION;
    if (!kyo_windows_acl_equal(expected->dacl, current->dacl) ||
        (control & KYO_DACL_CONTROL) != 0)
        information |= DACL_SECURITY_INFORMATION;
    if (kyo_windows_acl_type_changed(expected->sacl, current->sacl,
                                    SYSTEM_MANDATORY_LABEL_ACE_TYPE,
                                    (control & KYO_SACL_CONTROL) != 0))
        information |= LABEL_SECURITY_INFORMATION;
    if (kyo_windows_acl_type_changed(expected->sacl, current->sacl,
                                    SYSTEM_RESOURCE_ATTRIBUTE_ACE_TYPE,
                                    (control & KYO_SACL_CONTROL) != 0))
        information |= ATTRIBUTE_SECURITY_INFORMATION;
    if (kyo_windows_acl_type_changed(expected->sacl, current->sacl,
                                    SYSTEM_SCOPED_POLICY_ID_ACE_TYPE,
                                    (control & KYO_SACL_CONTROL) != 0))
        information |= SCOPE_SECURITY_INFORMATION;
    if (kyo_windows_acl_type_changed(expected->sacl, current->sacl,
                                    SYSTEM_PROCESS_TRUST_LABEL_ACE_TYPE,
                                    (control & KYO_SACL_CONTROL) != 0))
        information |= PROCESS_TRUST_LABEL_SECURITY_INFORMATION;
    if (kyo_windows_acl_type_changed(expected->sacl, current->sacl,
                                    SYSTEM_ACCESS_FILTER_ACE_TYPE,
                                    (control & KYO_SACL_CONTROL) != 0))
        information |= ACCESS_FILTER_SECURITY_INFORMATION;
    /* ACL control changes do not authorize touching unrelated security policy
       types. Use the types present in either ACL, or LABEL for an empty ACL. */
    if ((control & KYO_SACL_CONTROL) != 0 &&
        (information & KYO_ACCESS_SACL_INFORMATION) == 0)
        information |= LABEL_SECURITY_INFORMATION;
    return information;
}

static DWORD kyo_windows_security_restore(kyo_windows_durable *state) {
    kyo_windows_security current = {0};
    kyo_windows_security *expected = &state->security;
    SECURITY_INFORMATION information, modern, native;
    SECURITY_INFORMATION native_mask =
        OWNER_SECURITY_INFORMATION | GROUP_SECURITY_INFORMATION;
    DWORD error;
    if (expected->descriptor == NULL)
        return ERROR_SUCCESS;
    error = kyo_windows_security_read(state->file, &current);
    if (error != ERROR_SUCCESS)
        return error;
    /* SetSecurityInfo converts legacy ACLs to the automatic inheritance model,
       which can add inherited copies of their existing ACEs. NtSetSecurityObject
       preserves legacy ACLs, but clears AUTO_INHERITED on modern ones. Select
       the setter separately for each captured ACL instead of changing its model. */
    if (!(expected->control & SE_DACL_AUTO_INHERITED))
        native_mask |= DACL_SECURITY_INFORMATION;
    if (!(expected->control & SE_SACL_AUTO_INHERITED))
        native_mask |= KYO_ACCESS_SACL_INFORMATION;
    information = kyo_windows_security_changes(expected, &current);
    modern = information & ~native_mask;
    native = information & native_mask;
    if (modern & DACL_SECURITY_INFORMATION)
        modern |= (expected->control & SE_DACL_PROTECTED)
                      ? PROTECTED_DACL_SECURITY_INFORMATION
                      : UNPROTECTED_DACL_SECURITY_INFORMATION;
    if ((modern & KYO_ACCESS_SACL_INFORMATION) != 0 &&
        ((expected->control ^ current.control) &
         (SE_SACL_PROTECTED | SE_SACL_AUTO_INHERITED | SE_SACL_AUTO_INHERIT_REQ)) != 0)
        modern |= (expected->control & SE_SACL_PROTECTED)
                      ? PROTECTED_SACL_SECURITY_INFORMATION
                      : UNPROTECTED_SACL_SECURITY_INFORMATION;
    kyo_windows_security_free(&current);
    if (modern != 0) {
        error = SetSecurityInfo(state->file, SE_FILE_OBJECT, modern,
                                expected->owner, expected->group,
                                expected->dacl, expected->sacl);
        if (error != ERROR_SUCCESS)
            return error;
        /* Account for any legacy component changed by automatic propagation
           before restoring it through the same owned handle. */
        error = kyo_windows_security_read(state->file, &current);
        if (error != ERROR_SUCCESS)
            return error;
        native = kyo_windows_security_changes(expected, &current) & native_mask;
        kyo_windows_security_free(&current);
    }
    if (native != 0) {
        NTSTATUS status = NtSetSecurityObject(state->file, native,
                                               expected->descriptor);
        if (status < 0)
            return RtlNtStatusToDosError(status);
    }
    error = kyo_windows_security_read(state->file, &current);
    if (error != ERROR_SUCCESS)
        return error;
    /* Includes null versus absent ACLs and inheritance state. A successful
       setter alone does not prove that the complete access policy survived. */
    if (!kyo_windows_security_equal(expected, &current))
        error = ERROR_NOT_SUPPORTED;
    kyo_windows_security_free(&current);
    return error;
}

static DWORD kyo_windows_cleanup_acl(kyo_windows_durable *state, PSID owner) {
    DWORD size = sizeof(ACL) + sizeof(ACCESS_ALLOWED_ACE) - sizeof(DWORD) +
                 GetLengthSid(owner);
    state->cleanup_acl = (PACL)malloc(size);
    if (state->cleanup_acl == NULL)
        return ERROR_NOT_ENOUGH_MEMORY;
    if (!InitializeAcl(state->cleanup_acl, size, ACL_REVISION) ||
        !AddAccessAllowedAceEx(state->cleanup_acl, ACL_REVISION, 0,
                              DELETE | FILE_READ_ATTRIBUTES | READ_CONTROL,
                              owner))
        return GetLastError();
    return ERROR_SUCCESS;
}

/* A failed restoration may already have changed the owner or denied deletion.
   Retained rights let cleanup revoke content access and grant only deletion to
   the creator. A reset failure is returned by close, separately from the
   original sync failure, so the caller's cleanup reporting retains both. */
static int32_t kyo_windows_sync_failure(kyo_windows_durable *state, DWORD primary) {
    FILE_BASIC_INFO basic;
    DWORD error = ERROR_SUCCESS, acl_error;
    if (state->cleanup_acl == NULL)
        return (int32_t)primary;
    if (!GetFileInformationByHandleEx(state->file, FileBasicInfo, &basic,
                                      sizeof(basic))) {
        error = GetLastError();
    } else if (basic.FileAttributes & FILE_ATTRIBUTE_READONLY) {
        basic.FileAttributes &= ~FILE_ATTRIBUTE_READONLY;
        if (basic.FileAttributes == 0)
            basic.FileAttributes = FILE_ATTRIBUTE_NORMAL;
        if (!SetFileInformationByHandle(state->file, FileBasicInfo, &basic,
                                         sizeof(basic)))
            error = GetLastError();
    }
    acl_error = SetSecurityInfo(state->file, SE_FILE_OBJECT,
                                DACL_SECURITY_INFORMATION |
                                    PROTECTED_DACL_SECURITY_INFORMATION,
                                NULL, NULL, state->cleanup_acl, NULL);
    if (error == ERROR_SUCCESS)
        error = acl_error;
    if (state->cleanup_error == ERROR_SUCCESS)
        state->cleanup_error = error;
    return (int32_t)primary;
}

/* Resolve before adding the extended path prefix, which disables normalization. */
static DWORD kyo_windows_path(const char *input, WCHAR **output) {
    WCHAR *utf16 = NULL, *absolute = NULL, *extended = NULL;
    int length;
    DWORD needed, written, error = ERROR_SUCCESS;
    size_t prefix, characters, i;
    *output = NULL;
    if (input == NULL)
        return ERROR_INVALID_PARAMETER;
    length = MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input, -1,
                                 NULL, 0);
    if (length == 0)
        return GetLastError();
    utf16 = (WCHAR *)malloc((size_t)length * sizeof(WCHAR));
    if (utf16 == NULL)
        return ERROR_NOT_ENOUGH_MEMORY;
    if (!MultiByteToWideChar(CP_UTF8, MB_ERR_INVALID_CHARS, input, -1, utf16,
                             length)) {
        error = GetLastError();
        goto done;
    }
    for (i = 0; i < (size_t)length; ++i)
        if (utf16[i] == L'/')
            utf16[i] = L'\\';
    if (wcsncmp(utf16, L"\\\\?\\", 4) == 0 ||
        wcsncmp(utf16, L"\\\\.\\", 4) == 0) {
        *output = utf16;
        return ERROR_SUCCESS;
    }
    needed = GetFullPathNameW(utf16, 0, NULL, NULL);
    if (needed == 0) {
        error = GetLastError();
        goto done;
    }
    absolute = (WCHAR *)malloc((size_t)needed * sizeof(WCHAR));
    if (absolute == NULL) {
        error = ERROR_NOT_ENOUGH_MEMORY;
        goto done;
    }
    written = GetFullPathNameW(utf16, needed, absolute, NULL);
    if (written == 0 || written >= needed) {
        error = written == 0 ? GetLastError() : ERROR_INSUFFICIENT_BUFFER;
        goto done;
    }
    prefix = absolute[0] == L'\\' && absolute[1] == L'\\' ? 8 : 4;
    characters = (size_t)written + prefix + 1;
    extended = (WCHAR *)malloc(characters * sizeof(WCHAR));
    if (extended == NULL) {
        error = ERROR_NOT_ENOUGH_MEMORY;
        goto done;
    }
    if (prefix == 8) {
        memcpy(extended, L"\\\\?\\UNC\\", 8 * sizeof(WCHAR));
        wcscpy(extended + 8, absolute + 2);
    } else {
        memcpy(extended, L"\\\\?\\", 4 * sizeof(WCHAR));
        wcscpy(extended + 4, absolute);
    }
    *output = extended;
done:
    free(absolute);
    free(utf16);
    return error;
}

static int32_t kyo_windows_error_kind(DWORD error) {
    switch (error) {
    case ERROR_FILE_EXISTS:
    case ERROR_ALREADY_EXISTS:
        return 1;
    case ERROR_ACCESS_DENIED:
    case ERROR_PRIVILEGE_NOT_HELD:
    case ERROR_SHARING_VIOLATION:
    case ERROR_WRITE_PROTECT:
        return 2;
    case ERROR_DIRECTORY:
    case ERROR_PATH_NOT_FOUND:
        return 3;
    default:
        return 4;
    }
}

static DWORD kyo_windows_discard(HANDLE file) {
    FILE_DISPOSITION_INFO disposition;
    DWORD error = ERROR_SUCCESS;
    disposition.DeleteFile = TRUE;
    /* DELETE was acquired at creation, before ownership or ACLs can change. */
    if (!SetFileInformationByHandle(file, FileDispositionInfo, &disposition,
                                    sizeof(disposition)))
        error = GetLastError();
    if (!CloseHandle(file) && error == ERROR_SUCCESS)
        error = GetLastError();
    return error;
}

static DWORD kyo_windows_current_user(TOKEN_USER **result) {
    HANDLE token;
    TOKEN_USER *user = NULL;
    DWORD size = 0, error;
    *result = NULL;
    if (!OpenThreadToken(GetCurrentThread(), TOKEN_QUERY, TRUE, &token)) {
        error = GetLastError();
        if (error != ERROR_NO_TOKEN)
            return error;
        if (!OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token))
            return GetLastError();
    }
    if (GetTokenInformation(token, TokenUser, NULL, 0, &size)) {
        error = ERROR_INVALID_DATA;
        goto done;
    }
    error = GetLastError();
    if (error != ERROR_INSUFFICIENT_BUFFER)
        goto done;
    user = (TOKEN_USER *)malloc(size);
    if (user == NULL) {
        error = ERROR_NOT_ENOUGH_MEMORY;
        goto done;
    }
    if (!GetTokenInformation(token, TokenUser, user, size, &size)) {
        error = GetLastError();
        goto done;
    }
    error = IsValidSid(user->User.Sid) ? ERROR_SUCCESS : ERROR_INVALID_SID;
done:
    if (!CloseHandle(token) && error == ERROR_SUCCESS)
        error = GetLastError();
    if (error == ERROR_SUCCESS)
        *result = user;
    else
        free(user);
    return error;
}

static DWORD kyo_windows_private_directory_acl(PSID owner, PACL *result) {
    DWORD size = sizeof(ACL) + sizeof(ACCESS_ALLOWED_ACE) - sizeof(DWORD) +
                 GetLengthSid(owner);
    PACL acl = (PACL)malloc(size);
    *result = NULL;
    if (acl == NULL)
        return ERROR_NOT_ENOUGH_MEMORY;
    /* Windows normally bypasses directory traversal checks. Inherit the same
       private grant into children so a known child name cannot bypass privacy. */
    if (!InitializeAcl(acl, size, ACL_REVISION) ||
        !AddAccessAllowedAceEx(acl, ACL_REVISION,
                              OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE,
                              FILE_ALL_ACCESS, owner)) {
        DWORD error = GetLastError();
        free(acl);
        return error;
    }
    *result = acl;
    return ERROR_SUCCESS;
}

static DWORD kyo_windows_verify_directory(HANDLE directory, PSID owner,
                                           PACL expected_acl) {
    BY_HANDLE_FILE_INFORMATION metadata;
    kyo_windows_security actual = {0};
    DWORD error, flags;
    if (!GetFileInformationByHandle(directory, &metadata))
        return GetLastError();
    if (!(metadata.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY))
        return ERROR_DIRECTORY;
    if (metadata.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT)
        return ERROR_NOT_SUPPORTED;
    if (!GetVolumeInformationByHandleW(directory, NULL, 0, NULL, NULL,
                                       &flags, NULL, 0))
        return GetLastError();
    if (!(flags & FILE_PERSISTENT_ACLS))
        return ERROR_NOT_SUPPORTED;
    error = kyo_windows_security_read(directory, &actual);
    if (error == ERROR_SUCCESS &&
        (!EqualSid(actual.owner, owner) ||
         (actual.control & (SE_DACL_PRESENT | SE_DACL_PROTECTED)) !=
             (SE_DACL_PRESENT | SE_DACL_PROTECTED) ||
         !kyo_windows_acl_equal(actual.dacl, expected_acl)))
        error = ERROR_NOT_SUPPORTED;
    kyo_windows_security_free(&actual);
    return error;
}

KYO_DURABLE_EXPORT int32_t kyo_durable_verify_directory(const char *path,
                                                        int32_t *result_error) {
    WCHAR *directory_path = NULL;
    TOKEN_USER *user = NULL;
    PACL acl = NULL;
    HANDLE directory = INVALID_HANDLE_VALUE;
    DWORD error;
    if (result_error == NULL)
        return -1;
    result_error[0] = result_error[1] = 0;
    error = kyo_windows_path(path, &directory_path);
    if (error != ERROR_SUCCESS)
        goto done;
    error = kyo_windows_current_user(&user);
    if (error != ERROR_SUCCESS)
        goto done;
    error = kyo_windows_private_directory_acl(user->User.Sid, &acl);
    if (error != ERROR_SUCCESS)
        goto done;
    /* Query the named entry itself and retain its identity during verification.
       No mutation or deletion rights are requested, including on failure. */
    directory = CreateFileW(directory_path, READ_CONTROL | FILE_READ_ATTRIBUTES,
                             FILE_SHARE_READ | FILE_SHARE_WRITE, NULL,
                             OPEN_EXISTING,
                             FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                             NULL);
    if (directory == INVALID_HANDLE_VALUE) {
        error = GetLastError();
        goto done;
    }
    error = kyo_windows_verify_directory(directory, user->User.Sid, acl);
done:
    if (directory != INVALID_HANDLE_VALUE && !CloseHandle(directory) &&
        error == ERROR_SUCCESS)
        error = GetLastError();
    free(acl);
    free(user);
    free(directory_path);
    if (error != ERROR_SUCCESS) {
        result_error[0] = kyo_windows_error_kind(error);
        result_error[1] = (int32_t)error;
        return -1;
    }
    return 0;
}

KYO_DURABLE_EXPORT int32_t kyo_durable_mkdir(const char *path,
                                           int32_t *result_error) {
    WCHAR *directory_path = NULL;
    TOKEN_USER *user = NULL;
    PACL acl = NULL;
    HANDLE directory = INVALID_HANDLE_VALUE;
    SECURITY_DESCRIPTOR descriptor;
    UNICODE_STRING object_name;
    OBJECT_ATTRIBUTES attributes;
    IO_STATUS_BLOCK io_status;
    NTSTATUS status;
    DWORD error;
    size_t path_size;
    BOOL created = FALSE;
    if (result_error == NULL)
        return -1;
    result_error[0] = result_error[1] = 0;
    error = kyo_windows_path(path, &directory_path);
    if (error != ERROR_SUCCESS)
        goto done;
    error = kyo_windows_current_user(&user);
    if (error != ERROR_SUCCESS)
        goto done;
    error = kyo_windows_private_directory_acl(user->User.Sid, &acl);
    if (error != ERROR_SUCCESS)
        goto done;
    if (!InitializeSecurityDescriptor(&descriptor,
                                        SECURITY_DESCRIPTOR_REVISION) ||
        !SetSecurityDescriptorOwner(&descriptor, user->User.Sid, FALSE) ||
        !SetSecurityDescriptorDacl(&descriptor, TRUE, acl, FALSE) ||
        !SetSecurityDescriptorControl(&descriptor, SE_DACL_PROTECTED,
                                        SE_DACL_PROTECTED)) {
        error = GetLastError();
        goto done;
    }
    /* The path helper produces the Win32 device namespace (\\\\?\\ or \\\\.\\).
       Its NT equivalent is \\??\\, including \\??\\UNC\\ for network paths. */
    path_size = wcslen(directory_path) * sizeof(WCHAR);
    if (path_size > UINT16_MAX - sizeof(WCHAR)) {
        error = ERROR_FILENAME_EXCED_RANGE;
        goto done;
    }
    directory_path[1] = directory_path[2] = L'?';
    object_name.Buffer = directory_path;
    object_name.Length = (USHORT)path_size;
    object_name.MaximumLength = (USHORT)(path_size + sizeof(WCHAR));
    InitializeObjectAttributes(&attributes, &object_name, OBJ_CASE_INSENSITIVE,
                               NULL, &descriptor);
    /* Creation must return the handle atomically: reopening a created path can
       select a substituted object. Excluding delete sharing prevents replacement
       while the exact created directory is being verified. */
    memset(&io_status, 0, sizeof(io_status));
    status = NtCreateFile(&directory,
                           READ_CONTROL | FILE_READ_ATTRIBUTES | DELETE | SYNCHRONIZE,
                           &attributes, &io_status, NULL, FILE_ATTRIBUTE_NORMAL,
                           FILE_SHARE_READ | FILE_SHARE_WRITE, FILE_CREATE,
                           FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT | FILE_OPEN_REPARSE_POINT,
                           NULL, 0);
    if (status < 0) {
        directory = INVALID_HANDLE_VALUE;
        error = RtlNtStatusToDosError(status);
        goto done;
    }
    if (io_status.Information != FILE_CREATED) {
        error = ERROR_NOT_SUPPORTED;
        goto done;
    }
    created = TRUE;
    error = kyo_windows_verify_directory(directory, user->User.Sid, acl);
    if (error != ERROR_SUCCESS)
        goto done;
    if (!CloseHandle(directory)) {
        error = GetLastError();
        goto done;
    }
    directory = INVALID_HANDLE_VALUE;
    created = FALSE;
done:
    if (directory != INVALID_HANDLE_VALUE) {
        /* Handle-based deletion cannot follow a substituted pathname. It also
           fails for nonempty directories instead of deleting their contents. */
        DWORD cleanup_error = created ? kyo_windows_discard(directory)
                                      : (CloseHandle(directory) ? ERROR_SUCCESS
                                                                : GetLastError());
        if (error == ERROR_SUCCESS)
            error = cleanup_error;
    }
    free(acl);
    free(user);
    free(directory_path);
    if (error != ERROR_SUCCESS) {
        result_error[0] = kyo_windows_error_kind(error);
        result_error[1] = (int32_t)error;
        return -1;
    }
    return 0;
}

/* DELETE_CHILD on the named parent permits replacement even when the target's
   DACL grants no DELETE access. Open the directory without backup intent so
   enabled backup/restore privileges cannot stand in for ordinary authorization. */
static DWORD kyo_windows_parent_delete(const WCHAR *target) {
    const WCHAR *separator = wcsrchr(target, L'\\');
    WCHAR *parent;
    size_t characters, bytes;
    UNICODE_STRING name;
    OBJECT_ATTRIBUTES attributes;
    IO_STATUS_BLOCK io;
    HANDLE directory;
    NTSTATUS status;
    if (separator == NULL)
        return ERROR_INVALID_NAME;
    characters = (size_t)(separator - target) + 1;
    bytes = characters * sizeof(WCHAR);
    if (characters < 4 || bytes > UINT16_MAX - sizeof(WCHAR))
        return ERROR_INVALID_NAME;
    parent = (WCHAR *)malloc(bytes + sizeof(WCHAR));
    if (parent == NULL)
        return ERROR_NOT_ENOUGH_MEMORY;
    memcpy(parent, target, bytes);
    parent[characters] = 0;
    parent[1] = parent[2] = L'?';
    name.Buffer = parent;
    name.Length = (USHORT)bytes;
    name.MaximumLength = (USHORT)(bytes + sizeof(WCHAR));
    InitializeObjectAttributes(&attributes, &name, OBJ_CASE_INSENSITIVE, NULL, NULL);
    memset(&io, 0, sizeof(io));
    status = NtCreateFile(&directory, FILE_DELETE_CHILD | SYNCHRONIZE,
                           &attributes, &io, NULL, FILE_ATTRIBUTE_NORMAL,
                           FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                           FILE_OPEN, FILE_DIRECTORY_FILE | FILE_SYNCHRONOUS_IO_NONALERT,
                           NULL, 0);
    free(parent);
    if (status < 0)
        return RtlNtStatusToDosError(status);
    return CloseHandle(directory) ? ERROR_SUCCESS : GetLastError();
}

KYO_DURABLE_EXPORT int64_t kyo_durable_open(const char *target,
                                          const char *temporary,
                                          int32_t *result_error) {
    WCHAR *target_path = NULL, *temporary_path = NULL;
    HANDLE source = INVALID_HANDLE_VALUE;
    kyo_windows_durable *state = NULL;
    SECURITY_DESCRIPTOR restrictive;
    ACL empty_acl;
    SECURITY_ATTRIBUTES attributes;
    BY_HANDLE_FILE_INFORMATION source_info;
    DWORD error, flags, desired_access;
    BOOL security_privilege = FALSE;
    if (result_error == NULL)
        return 0;
    result_error[0] = result_error[1] = 0;
    error = kyo_windows_path(target, &target_path);
    if (error != ERROR_SUCCESS)
        goto failed;
    error = kyo_windows_path(temporary, &temporary_path);
    if (error != ERROR_SUCCESS)
        goto failed;
    state = (kyo_windows_durable *)calloc(1, sizeof(*state));
    if (state == NULL) {
        error = ERROR_NOT_ENOUGH_MEMORY;
        goto failed;
    }
    state->file = INVALID_HANDLE_VALUE;
    source = CreateFileW(target_path, READ_CONTROL | FILE_READ_ATTRIBUTES,
                         FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                         NULL, OPEN_EXISTING,
                         FILE_FLAG_OPEN_REPARSE_POINT | FILE_FLAG_BACKUP_SEMANTICS,
                         NULL);
    if (source == INVALID_HANDLE_VALUE) {
        error = GetLastError();
        if (error != ERROR_FILE_NOT_FOUND)
            goto failed;
    } else {
        if (GetFileType(source) != FILE_TYPE_DISK) {
            error = ERROR_NOT_SUPPORTED;
            goto failed;
        }
        if (!GetFileInformationByHandle(source, &source_info)) {
            error = GetLastError();
            goto failed;
        }
        if (source_info.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) {
            error = ERROR_DIRECTORY;
            goto failed;
        }
        if (source_info.dwFileAttributes & FILE_ATTRIBUTE_READONLY) {
            /* The generic atomic replacement cannot replace a readonly file. */
            error = ERROR_ACCESS_DENIED;
            goto failed;
        }
        /* Replacing encrypted bytes with plaintext can broaden access even
           when the DACL is identical. This helper does not transfer EFS keys. */
        if (source_info.dwFileAttributes &
            (FILE_ATTRIBUTE_REPARSE_POINT | FILE_ATTRIBUTE_ENCRYPTED)) {
            error = ERROR_NOT_SUPPORTED;
            goto failed;
        }
        if (!GetVolumeInformationByHandleW(source, NULL, 0, NULL, NULL,
                                            &flags, NULL, 0)) {
            error = GetLastError();
            goto failed;
        }
        if (!(flags & FILE_PERSISTENT_ACLS)) {
            error = ERROR_NOT_SUPPORTED;
            goto failed;
        }
        error = kyo_windows_security_read(source, &state->security);
        if (error != ERROR_SUCCESS)
            goto failed;
        /* Check ordinary rename authorization on this exact object. Backup
           intent can bypass DELETE checks for privileged callers, while the
           later rename still requires DELETE or the parent's FILE_DELETE_CHILD.
           A by-handle reopen checks the target DACL; the parent's ordinary
           DELETE_CHILD grant is checked separately if the target denies DELETE. */
        {
            HANDLE deletion = ReOpenFile(
                source, DELETE,
                FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                FILE_FLAG_OPEN_REPARSE_POINT);
            if (deletion == INVALID_HANDLE_VALUE) {
                error = GetLastError();
                if (error == ERROR_ACCESS_DENIED)
                    error = kyo_windows_parent_delete(target_path);
                if (error != ERROR_SUCCESS)
                    goto failed;
            } else if (!CloseHandle(deletion)) {
                error = GetLastError();
                goto failed;
            }
        }
        if (!CloseHandle(source)) {
            error = GetLastError();
            goto failed;
        }
        source = INVALID_HANDLE_VALUE;
        error = kyo_windows_security_privilege(&security_privilege);
        if (error != ERROR_SUCCESS)
            goto failed;
    }
    desired_access = GENERIC_WRITE | READ_CONTROL | DELETE |
                     FILE_READ_ATTRIBUTES | FILE_WRITE_ATTRIBUTES;
    if (state->security.descriptor != NULL) {
        desired_access |= WRITE_DAC | WRITE_OWNER;
        if (security_privilege)
            desired_access |= ACCESS_SYSTEM_SECURITY;
        if (!InitializeSecurityDescriptor(&restrictive,
                                           SECURITY_DESCRIPTOR_REVISION) ||
            !InitializeAcl(&empty_acl, sizeof(empty_acl), ACL_REVISION) ||
            !SetSecurityDescriptorDacl(&restrictive, TRUE, &empty_acl, FALSE) ||
            !SetSecurityDescriptorControl(&restrictive, SE_DACL_PROTECTED,
                                           SE_DACL_PROTECTED)) {
            error = GetLastError();
            goto failed;
        }
        attributes.nLength = sizeof(attributes);
        attributes.lpSecurityDescriptor = &restrictive;
        attributes.bInheritHandle = FALSE;
    }
    /* The protected empty DACL blocks inherited grants from the first instant.
       Sharing mode zero also prevents another reader retaining a handle while
       final permissions are applied. The native handle never enters a CRT. */
    state->file = CreateFileW(temporary_path, desired_access, 0,
                              state->security.descriptor != NULL ? &attributes : NULL,
                              CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (state->file == INVALID_HANDLE_VALUE) {
        error = GetLastError();
        goto failed;
    }
    if (state->security.descriptor != NULL) {
        kyo_windows_security staging = {0};
        BY_HANDLE_FILE_INFORMATION temporary_info;
        error = kyo_windows_security_read(state->file, &staging);
        if (error != ERROR_SUCCESS)
            goto failed;
        if ((staging.control & (SE_DACL_PRESENT | SE_DACL_PROTECTED)) !=
                (SE_DACL_PRESENT | SE_DACL_PROTECTED) ||
            staging.dacl == NULL || staging.dacl->AceCount != 0)
            error = ERROR_NOT_SUPPORTED;
        if (error == ERROR_SUCCESS)
            error = kyo_windows_cleanup_acl(state, staging.owner);
        kyo_windows_security_free(&staging);
        if (error != ERROR_SUCCESS)
            goto failed;
        if (!GetFileInformationByHandle(state->file, &temporary_info)) {
            error = GetLastError();
            goto failed;
        }
        /* Encryption inherited from the directory also changes who can read. */
        if (temporary_info.dwFileAttributes & FILE_ATTRIBUTE_ENCRYPTED) {
            error = ERROR_NOT_SUPPORTED;
            goto failed;
        }
    }
    free(target_path);
    free(temporary_path);
    return (int64_t)(intptr_t)state;
failed:
    if (source != INVALID_HANDLE_VALUE)
        CloseHandle(source);
    if (state != NULL) {
        if (state->file != INVALID_HANDLE_VALUE) {
            DWORD cleanup_error = kyo_windows_discard(state->file);
            if (cleanup_error != ERROR_SUCCESS)
                error = cleanup_error;
        }
        kyo_windows_security_free(&state->security);
        free(state->cleanup_acl);
        free(state);
    }
    free(target_path);
    free(temporary_path);
    result_error[0] = kyo_windows_error_kind(error);
    result_error[1] = (int32_t)error;
    return 0;
}

KYO_DURABLE_EXPORT int32_t kyo_durable_write(int64_t handle, int64_t position,
                                           const unsigned char *bytes,
                                           int32_t length) {
    kyo_windows_durable *state = (kyo_windows_durable *)(intptr_t)handle;
    DWORD remaining, written;
    uint64_t offset;
    if (state == NULL || position < 0 || length < 0 ||
        (length != 0 && bytes == NULL) || position > INT64_MAX - length)
        return ERROR_INVALID_PARAMETER;
    remaining = (DWORD)length;
    offset = (uint64_t)position;
    while (remaining != 0) {
        OVERLAPPED at = {0};
        at.Offset = (DWORD)offset;
        at.OffsetHigh = (DWORD)(offset >> 32);
        /* A synchronous handle honors OVERLAPPED offsets without a shared
           SetFilePointerEx cursor or a separately allocated event. */
        if (!WriteFile(state->file, bytes, remaining, &written, &at))
            return (int32_t)GetLastError();
        if (written == 0)
            return ERROR_WRITE_FAULT;
        bytes += written;
        offset += written;
        remaining -= written;
    }
    return ERROR_SUCCESS;
}

KYO_DURABLE_EXPORT int32_t kyo_durable_truncate(int64_t handle, int64_t size) {
    kyo_windows_durable *state = (kyo_windows_durable *)(intptr_t)handle;
    FILE_END_OF_FILE_INFO end;
    if (state == NULL || size < 0)
        return ERROR_INVALID_PARAMETER;
    end.EndOfFile.QuadPart = size;
    if (!SetFileInformationByHandle(state->file, FileEndOfFileInfo, &end,
                                    sizeof(end)))
        return (int32_t)GetLastError();
    return ERROR_SUCCESS;
}

KYO_DURABLE_EXPORT int32_t kyo_durable_sync(int64_t handle) {
    kyo_windows_durable *state = (kyo_windows_durable *)(intptr_t)handle;
    FILE_BASIC_INFO basic;
    DWORD error;
    if (state == NULL)
        return ERROR_INVALID_PARAMETER;
    error = kyo_windows_security_restore(state);
    if (error != ERROR_SUCCESS)
        return kyo_windows_sync_failure(state, error);
    if (state->security.descriptor != NULL) {
        if (!GetFileInformationByHandleEx(state->file, FileBasicInfo, &basic,
                                           sizeof(basic)))
            return kyo_windows_sync_failure(state, GetLastError());
        if (basic.FileAttributes & FILE_ATTRIBUTE_READONLY) {
            basic.FileAttributes &= ~FILE_ATTRIBUTE_READONLY;
            if (basic.FileAttributes == 0)
                basic.FileAttributes = FILE_ATTRIBUTE_NORMAL;
            if (!SetFileInformationByHandle(state->file, FileBasicInfo, &basic,
                                             sizeof(basic)))
                return kyo_windows_sync_failure(state, GetLastError());
            if (!GetFileInformationByHandleEx(state->file, FileBasicInfo, &basic,
                                               sizeof(basic)))
                return kyo_windows_sync_failure(state, GetLastError());
            if (basic.FileAttributes & FILE_ATTRIBUTE_READONLY)
                return kyo_windows_sync_failure(state, ERROR_NOT_SUPPORTED);
        }
    }
    if (!FlushFileBuffers(state->file))
        return kyo_windows_sync_failure(state, GetLastError());
    return ERROR_SUCCESS;
}

KYO_DURABLE_EXPORT int32_t kyo_durable_close(int64_t handle) {
    kyo_windows_durable *state = (kyo_windows_durable *)(intptr_t)handle;
    DWORD error;
    if (state == NULL)
        return ERROR_INVALID_PARAMETER;
    error = state->cleanup_error;
    if (!CloseHandle(state->file) && error == ERROR_SUCCESS)
        error = GetLastError();
    kyo_windows_security_free(&state->security);
    free(state->cleanup_acl);
    free(state);
    return (int32_t)error;
}

#endif
