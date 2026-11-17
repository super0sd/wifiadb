package com.yg.wifiadb.core;

import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/** ADB 认证密钥：本地 RSA 2048，签名 AUTH token，并按 adb 结构导出公钥。 */
public class AdbCrypto {

    private static final String KEY_NAME = "adbkey";
    private static final String PUB_NAME = "adbkey.pub";
    private static final String KEY_ALIAS = "wifiadb@android";

    private final KeyPair keyPair;

    private AdbCrypto(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static AdbCrypto loadOrCreate(File dir) {
        try {
            File priv = new File(dir, KEY_NAME);
            File pub = new File(dir, PUB_NAME);
            if (priv.exists() && pub.exists()) {
                KeyPair kp = readKeyPair(priv, pub);
                if (kp != null) {
                    return new AdbCrypto(kp);
                }
            }
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair kp = generator.generateKeyPair();
            writeKeyPair(dir, kp);
            return new AdbCrypto(kp);
        } catch (Exception e) {
            throw new IllegalStateException("初始化 ADB 密钥失败: " + e.getMessage(), e);
        }
    }

    public byte[] sign(byte[] token) {
        try {
            Signature signature = Signature.getInstance("SHA1withRSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(token);
            byte[] out = signature.sign();
            android.util.Log.d("WiFiAdb", "sign token=" + token.length + " sig=" + out.length);
            return out;
        } catch (Exception e) {
            android.util.Log.d("WiFiAdb", "sign failed: " + e);
            return new byte[0];
        }
    }

    /** adb 公钥结构：len, n0inv, n[64], rr[64], exponent，然后 base64 + " user@host"。 */
    public String adbPublicKeyString() {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        BigInteger n = pub.getModulus();
        BigInteger exponent = pub.getPublicExponent();
        int words = 64;
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4);
        bb.order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(words);
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        try {
            BigInteger inv = n.modInverse(r32);
            bb.putInt(inv.negate().intValue());
        } catch (ArithmeticException e) {
            bb.putInt(0);
        }
        putWords(bb, n, words);
        BigInteger r = BigInteger.ONE.shiftLeft(32 * words);
        BigInteger rr = r.multiply(r).mod(n);
        putWords(bb, rr, words);
        bb.putInt(exponent.intValue());
        String base64 = Base64.encodeToString(bb.array(), Base64.NO_WRAP);
        return base64 + " " + KEY_ALIAS;
    }

    private static void putWords(ByteBuffer bb, BigInteger value, int words) {
        for (int i = 0; i < words; i++) {
            bb.putInt(value.shiftRight(32 * i).intValue());
        }
    }

    private static void writeKeyPair(File dir, KeyPair kp) throws Exception {
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("无法创建密钥目录");
        }
        FileOutputStream privOut = new FileOutputStream(new File(dir, KEY_NAME));
        try {
            privOut.write(kp.getPrivate().getEncoded());
        } finally {
            privOut.close();
        }
        FileOutputStream pubOut = new FileOutputStream(new File(dir, PUB_NAME));
        try {
            pubOut.write(kp.getPublic().getEncoded());
        } finally {
            pubOut.close();
        }
    }

    private static KeyPair readKeyPair(File priv, File pub) {
        try {
            byte[] privBytes = readAll(priv);
            byte[] pubBytes = readAll(pub);
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(pubBytes));
            return new KeyPair(publicKey, privateKey);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readAll(File file) throws Exception {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] data = new byte[(int) file.length()];
            int offset = 0;
            while (offset < data.length) {
                int r = in.read(data, offset, data.length - offset);
                if (r < 0) {
                    break;
                }
                offset += r;
            }
            return data;
        } finally {
            in.close();
        }
    }
}
