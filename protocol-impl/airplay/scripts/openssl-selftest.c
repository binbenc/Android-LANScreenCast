/*
 * WirelessScreenCasting — 裁剪后 libcrypto 的算法可用性自检（GPL-3.0）。
 * 由 build-native-deps.sh --host-selftest 用与 Android 相同的 OpenSSL 选项在本机编译运行；
 * 检查项与 bridge/uxplay_jni.c 的 native_self_test 一致，并额外做一次 UxPlay 风格的 Ed25519 PEM 往返与 X25519 协商。
 */
#include <stdio.h>
#include <string.h>
#include <openssl/bio.h>
#include <openssl/bn.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/rand.h>

static int fail(const char *what) { fprintf(stderr, "openssl-selftest: FAIL %s\n", what); return 1; }

static EVP_PKEY *keygen(int id) {
    EVP_PKEY *k = NULL;
    EVP_PKEY_CTX *ctx = EVP_PKEY_CTX_new_id(id, NULL);
    if (!ctx || EVP_PKEY_keygen_init(ctx) <= 0 || EVP_PKEY_keygen(ctx, &k) <= 0) k = NULL;
    EVP_PKEY_CTX_free(ctx);
    return k;
}

int main(void) {
    const char *ciphers[] = {"AES-128-GCM", "AES-128-CTR", "AES-128-CBC"};
    const char *digests[] = {"SHA1", "SHA224", "SHA256", "SHA384", "SHA512", "MD5"};
    for (size_t i = 0; i < sizeof(ciphers) / sizeof(*ciphers); i++) {
        EVP_CIPHER *c = EVP_CIPHER_fetch(NULL, ciphers[i], NULL);
        if (!c) return fail(ciphers[i]);
        EVP_CIPHER_free(c);
    }
    for (size_t i = 0; i < sizeof(digests) / sizeof(*digests); i++) {
        EVP_MD *m = EVP_MD_fetch(NULL, digests[i], NULL);
        if (!m) return fail(digests[i]);
        EVP_MD_free(m);
    }
    /* Ed25519：生成 → PEM 写出 → 读回 → 签名 / 验签（UxPlay crypto.c 的持久化密钥路径） */
    EVP_PKEY *ed = keygen(EVP_PKEY_ED25519);
    if (!ed) return fail("ED25519 keygen");
    BIO *bio = BIO_new(BIO_s_mem());
    if (!PEM_write_bio_PrivateKey(bio, ed, NULL, NULL, 0, NULL, NULL)) return fail("PEM write");
    EVP_PKEY *ed2 = PEM_read_bio_PrivateKey(bio, NULL, NULL, NULL);
    if (!ed2) return fail("PEM read");
    unsigned char msg[32], sig[64];
    size_t siglen = sizeof(sig);
    RAND_bytes(msg, sizeof(msg));
    EVP_MD_CTX *mctx = EVP_MD_CTX_new();
    if (EVP_DigestSignInit(mctx, NULL, NULL, NULL, ed2) <= 0 || EVP_DigestSign(mctx, sig, &siglen, msg, sizeof(msg)) <= 0) return fail("ED25519 sign");
    EVP_MD_CTX_reset(mctx);
    if (EVP_DigestVerifyInit(mctx, NULL, NULL, NULL, ed) <= 0 || EVP_DigestVerify(mctx, sig, siglen, msg, sizeof(msg)) != 1) return fail("ED25519 verify");
    /* X25519 协商 */
    EVP_PKEY *a = keygen(EVP_PKEY_X25519), *b = keygen(EVP_PKEY_X25519);
    if (!a || !b) return fail("X25519 keygen");
    unsigned char s1[32], s2[32];
    size_t l1 = sizeof(s1), l2 = sizeof(s2);
    EVP_PKEY_CTX *d1 = EVP_PKEY_CTX_new(a, NULL), *d2 = EVP_PKEY_CTX_new(b, NULL);
    if (EVP_PKEY_derive_init(d1) <= 0 || EVP_PKEY_derive_set_peer(d1, b) <= 0 || EVP_PKEY_derive(d1, s1, &l1) <= 0) return fail("X25519 derive");
    if (EVP_PKEY_derive_init(d2) <= 0 || EVP_PKEY_derive_set_peer(d2, a) <= 0 || EVP_PKEY_derive(d2, s2, &l2) <= 0) return fail("X25519 derive");
    if (l1 != l2 || memcmp(s1, s2, l1) != 0) return fail("X25519 mismatch");
    /* AES-128-GCM 加解密往返 */
    unsigned char key[16], iv[12], pt[64], ct[64], out[64], tag[16];
    int n = 0, m = 0;
    RAND_bytes(key, 16); RAND_bytes(iv, 12); RAND_bytes(pt, 64);
    EVP_CIPHER_CTX *cc = EVP_CIPHER_CTX_new();
    if (!EVP_EncryptInit_ex(cc, EVP_aes_128_gcm(), NULL, key, iv) || !EVP_EncryptUpdate(cc, ct, &n, pt, 64) ||
        !EVP_EncryptFinal_ex(cc, ct + n, &m) || !EVP_CIPHER_CTX_ctrl(cc, EVP_CTRL_GCM_GET_TAG, 16, tag)) return fail("GCM encrypt");
    EVP_CIPHER_CTX_reset(cc);
    if (!EVP_DecryptInit_ex(cc, EVP_aes_128_gcm(), NULL, key, iv) || !EVP_DecryptUpdate(cc, out, &n, ct, 64) ||
        !EVP_CIPHER_CTX_ctrl(cc, EVP_CTRL_GCM_SET_TAG, 16, tag) || EVP_DecryptFinal_ex(cc, out + n, &m) <= 0 || memcmp(pt, out, 64)) return fail("GCM decrypt");
    /* SRP 用到的大数模幂 */
    BIGNUM *x = BN_new(), *e = BN_new(), *mod = BN_new(), *r = BN_new();
    BN_CTX *bctx = BN_CTX_new();
    BN_rand(x, 256, 0, 0); BN_rand(e, 256, 0, 0); BN_rand(mod, 2048, 0, 1);
    if (!BN_mod_exp(r, x, e, mod, bctx)) return fail("BN_mod_exp");
    printf("openssl-selftest: OK (%s)\n", OpenSSL_version(OPENSSL_VERSION));
    return 0;
}
