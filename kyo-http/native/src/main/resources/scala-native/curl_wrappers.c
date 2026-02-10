/*
 * Fixed-signature wrappers for variadic curl functions.
 *
 * On ARM64, variadic arguments use a different calling convention
 * (stack-based) than fixed arguments (register-based). Scala Native's
 * @extern generates calls using the fixed-arg convention, so calling
 * variadic C functions directly passes arguments in the wrong location.
 *
 * These wrappers have fixed signatures and forward to the real
 * variadic functions, ensuring the compiler generates correct code.
 *
 * See: https://github.com/softwaremill/sttp/pull/2082
 */

#include <curl/curl.h>

/* curl_easy_setopt wrappers (variadic: CURLcode curl_easy_setopt(CURL*, CURLoption, ...)) */

CURLcode kyo_curl_easy_setopt_long(CURL *handle, CURLoption option, long param) {
    return curl_easy_setopt(handle, option, param);
}

CURLcode kyo_curl_easy_setopt_ptr(CURL *handle, CURLoption option, void *param) {
    return curl_easy_setopt(handle, option, param);
}

/* curl_easy_getinfo wrapper (variadic: CURLcode curl_easy_getinfo(CURL*, CURLINFO, ...)) */

CURLcode kyo_curl_easy_getinfo_ptr(CURL *handle, CURLINFO info, void *out) {
    return curl_easy_getinfo(handle, info, out);
}

/* curl_multi_setopt wrappers (variadic: CURLMcode curl_multi_setopt(CURLM*, CURLMoption, ...)) */

CURLMcode kyo_curl_multi_setopt_ptr(CURLM *multi, CURLMoption option, void *param) {
    return curl_multi_setopt(multi, option, param);
}
