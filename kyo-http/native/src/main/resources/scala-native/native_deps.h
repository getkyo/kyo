/* Required native dependency versions for kyo-http.
 * When updating h2o, also update:
 *   - .github/workflows/build-main.yml
 *   - .github/workflows/build-pr.yml
 */
#define KYO_H2O_VERSION_MAJOR 2
#define KYO_H2O_VERSION_MINOR 2
#define KYO_H2O_APT_PKG "libh2o-evloop-dev=2.2.5+dfsg2-8.1ubuntu3"
