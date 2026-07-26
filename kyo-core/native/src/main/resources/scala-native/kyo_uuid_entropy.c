#include <stdint.h>

#ifdef _WIN32
#include <windows.h>

typedef LONG(WINAPI *kyo_bcrypt_gen_random_fn)(
    void *algorithm,
    unsigned char *target,
    unsigned long length,
    unsigned long flags);

#define KYO_BCRYPT_USE_SYSTEM_PREFERRED_RNG 0x00000002UL
#define KYO_STATUS_DLL_NOT_FOUND ((int32_t)0xC0000135L)
#define KYO_STATUS_ENTRYPOINT_NOT_FOUND ((int32_t)0xC0000139L)
#define KYO_STATUS_UNSUCCESSFUL ((int32_t)0xC0000001L)

int32_t kyo_uuid_bcrypt_gen_random(unsigned char *target, int32_t length) {
    HMODULE module = LoadLibraryW(L"bcrypt.dll");
    if (module == NULL) {
        return KYO_STATUS_DLL_NOT_FOUND;
    }

    kyo_bcrypt_gen_random_fn generate =
        (kyo_bcrypt_gen_random_fn)GetProcAddress(module, "BCryptGenRandom");
    if (generate == NULL) {
        FreeLibrary(module);
        return KYO_STATUS_ENTRYPOINT_NOT_FOUND;
    }

    int32_t status = (int32_t)generate(
        NULL,
        target,
        (unsigned long)length,
        KYO_BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    if (!FreeLibrary(module) && status == 0) {
        return KYO_STATUS_UNSUCCESSFUL;
    }
    return status;
}

#else

int32_t kyo_uuid_bcrypt_gen_random(unsigned char *target, int32_t length) {
    (void)target;
    (void)length;
    return -1;
}

#endif
