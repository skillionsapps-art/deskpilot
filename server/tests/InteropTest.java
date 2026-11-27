// Mirrors android/.../net/Crypto.kt + Transport.kt Frames with plain javax.crypto, against the real Python server.
// Run: java tests/InteropTest.java <port> <password>
import javax.crypto.*;
import javax.crypto.spec.*;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.regex.*;

public class InteropTest {
    static SecretKeySpec derive(String pw, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(("deskpilot:" + pw).toCharArray(), salt, 100_000, 256);
        return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
    }
    static byte[] seal(SecretKeySpec k, byte[] p) throws Exception {
        byte[] iv = new byte[12]; new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, k, new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(p); byte[] out = new byte[12 + ct.length];
        System.arraycopy(iv, 0, out, 0, 12); System.arraycopy(ct, 0, out, 12, ct.length); return out;
    }
    static byte[] open(SecretKeySpec k, byte[] b) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, b, 0, 12));
        return c.doFinal(b, 12, b.length - 12);
    }
    static Object[] read(DataInputStream in) throws IOException {
        int n = in.readInt(); int flag = in.readUnsignedByte(); byte[] b = new byte[n - 1]; in.readFully(b); return new Object[]{flag, b};
    }
    static void write(DataOutputStream out, int flag, byte[] p) throws IOException {
        out.writeInt(p.length + 1); out.writeByte(flag); out.write(p); out.flush();
    }
    static String field(String json, String k) {
        Matcher m = Pattern.compile("\"" + k + "\":\\s*\"([^\"]*)\"").matcher(json); return m.find() ? m.group(1) : null;
    }
    public static void main(String[] a) throws Exception {
        try (Socket s = new Socket("127.0.0.1", Integer.parseInt(a[0]))) {
            DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            DataOutputStream out = new DataOutputStream(new BufferedOutputStream(s.getOutputStream()));
            String hello = new String((byte[]) read(in)[1], StandardCharsets.UTF_8);
            SecretKeySpec key = derive(a[1], Base64.getDecoder().decode(field(hello, "salt")));
            write(out, 1, seal(key, ("{\"type\":\"auth\",\"nonce\":\"" + field(hello, "nonce") + "\",\"device\":\"java\"}").getBytes()));
            Object[] w = read(in);
            if ((int) w[0] != 1) throw new RuntimeException("auth rejected: " + new String((byte[]) w[1]));
            String welcome = new String(open(key, (byte[]) w[1]), StandardCharsets.UTF_8);
            if (!welcome.contains("\"welcome\"")) throw new RuntimeException("no welcome");
            write(out, 1, seal(key, "{\"type\":\"action\",\"id\":7,\"remote\":\"spotify\",\"action\":\"next\"}".getBytes()));
            String res = new String(open(key, (byte[]) read(in)[1]), StandardCharsets.UTF_8);
            if (!res.contains("\"ok\":true")) throw new RuntimeException("action failed " + res);
            System.out.println("JAVA INTEROP OK (handshake, AES-GCM both directions, action)");
        }
    }
}
