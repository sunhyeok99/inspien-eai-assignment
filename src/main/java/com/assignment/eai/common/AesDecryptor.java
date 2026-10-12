package com.assignment.eai.common;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

/**
 * 접속 정보 복호화 가이드 구현.
 * <pre>
 *  Key  : 휴대폰 번호 문자열 → UTF-8 bytes → SHA-1 → 앞 16 byte (128bit)
 *  알고리즘 : AES-128 / ECB / PKCS5Padding
 *  입력 : Base64 인코딩 문자열 → 디코딩 후 복호화
 * </pre>
 */
public final class AesDecryptor {

    private static final String TRANSFORMATION = "AES/ECB/PKCS5Padding";
    private final SecretKeySpec key;

    public AesDecryptor(String phoneNumber) {
        this.key = new SecretKeySpec(deriveKey(phoneNumber), "AES");
    }

    static byte[] deriveKey(String phoneNumber) {
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(phoneNumber.getBytes(StandardCharsets.UTF_8));
            return Arrays.copyOf(sha1, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public String decrypt(String base64CipherText) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key);
            byte[] plain = cipher.doFinal(Base64.getMimeDecoder().decode(base64CipherText.trim()));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new EaiException(ErrorCode.CONFIG,
                    "접속 정보 복호화 실패 - 휴대폰 번호(키) 형식(010-1234-5678)을 확인하세요: " + e.getMessage(), e);
        }
    }

    /** 테스트(라운드트립 검증)용 */
    public String encrypt(String plainText) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
