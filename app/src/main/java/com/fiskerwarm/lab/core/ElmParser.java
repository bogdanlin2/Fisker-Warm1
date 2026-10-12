package com.fiskerwarm.lab.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict, bounded parser; partial replies never become a successful reading. */
public final class ElmParser {
    private ElmParser() {}
    private static final String[] ERRORS = {"NO DATA", "BUFFER FULL", "STOPPED", "CAN ERROR", "BUS ERROR",
            "UNABLE TO CONNECT", "ERROR", "BUS INIT", "SEARCHING", "?"};
    private static final Pattern VOLTAGE = Pattern.compile("^(\\d{1,2}(?:\\.\\d{1,2})?)\\s*V$", Pattern.CASE_INSENSITIVE);

    public static Double voltage(String reply) {
        if (reply == null || reply.length() > 256) return null;
        String s = reply.replace("\r", "\n").replace(">", "").trim();
        if (s.startsWith("ATRV\n")) s = s.substring(5).trim();
        Matcher m = VOLTAGE.matcher(s);
        if (!m.matches()) return null;
        double v = Double.parseDouble(m.group(1));
        return Double.isFinite(v) && v >= 0 && v <= 20 ? v : null;
    }

    public static byte[] identity(String reply, String expectedRx, int did) {
        return parseIdentity(reply, expectedRx, did, false);
    }
    public static byte[] identityFramed(String reply, String expectedRx, int did) {
        return parseIdentity(reply, expectedRx, did, true);
    }
    private static byte[] parseIdentity(String reply, String expectedRx, int did, boolean requireHeaders) {
        if (reply == null || reply.isEmpty() || reply.length() > 8192) throw bad("Reply missing or too large");
        String upper = reply.toUpperCase(Locale.ROOT);
        for (String e : ERRORS) if (upper.contains(e)) throw bad("Adapter status: " + e);
        String echo = String.format(Locale.ROOT, "22%04X", did);
        List<byte[]> rawFrames = new ArrayList<>();
        List<byte[]> numbered = new ArrayList<>();
        List<byte[]> plain = new ArrayList<>();
        Integer declared = null;
        for (String line : upper.replace("\r", "\n").replace(">", "").split("\n")) {
            String s = line.trim();
            if (s.isEmpty() || s.replace(" ", "").equals(echo)) continue;
            if (s.matches("[0-9A-F]{3}")) {
                if (declared != null) throw bad("Repeated declared length");
                declared = Integer.parseInt(s, 16); continue;
            }
            if (s.matches("[0-9A-F]+:.*")) {
                String[] pair = s.split(":", 2);
                int index = Integer.parseInt(pair[0], 16);
                if (index != numbered.size()) throw bad("Out-of-order formatted frame");
                numbered.add(hex(pair[1])); continue;
            }
            String compact = s.replaceAll("\\s", "");
            if (compact.length() >= 5 && compact.length() % 2 == 1 && compact.matches("[0-9A-F]+")) {
                String rx = compact.substring(0, 3);
                if (!rx.equals(expectedRx)) throw bad("Unexpected responder " + rx);
                rawFrames.add(hex(compact.substring(3))); continue;
            }
            // An explicit DLC between the 11-bit header and the data.
            String[] tokens = s.split("\\s+");
            if (tokens.length >= 3 && tokens[0].matches("[0-9A-F]{3}") && tokens[1].matches("[0-8]")) {
                if (!tokens[0].equals(expectedRx)) throw bad("Unexpected responder");
                byte[] frame = hex(String.join("", Arrays.copyOfRange(tokens, 2, tokens.length)));
                if (frame.length != Integer.parseInt(tokens[1])) throw bad("DLC mismatch");
                rawFrames.add(frame); continue;
            }
            plain.add(hex(s));
        }
        int modes = (rawFrames.isEmpty() ? 0 : 1) + (numbered.isEmpty() ? 0 : 1) + (plain.isEmpty() ? 0 : 1);
        if (modes != 1) throw bad("Mixed or empty ELM formats");
        if (requireHeaders && rawFrames.isEmpty()) throw bad("CAN responder not present in live reply");
        byte[] payload;
        if (!rawFrames.isEmpty()) {
            if (declared != null) throw bad("Raw response with formatted length");
            payload = isotp(rawFrames);
        } else {
            if (numbered.isEmpty() && plain.size() != 1) throw bad("Ambiguous bare response");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            for (byte[] b : numbered.isEmpty() ? plain : numbered) out.write(b, 0, b.length);
            payload = out.toByteArray();
            if (!numbered.isEmpty() && declared == null) throw bad("Formatted multi-frame missing declared length");
            if (declared != null) {
                if (declared < 3 || declared > 256 || payload.length < declared) throw bad("Truncated declared payload");
                // A numbered ELM frame may include final CAN padding.
                payload = Arrays.copyOf(payload, declared);
            }
        }
        if (payload.length >= 3 && (payload[0] & 255) == 0x7F) {
            throw bad(String.format(Locale.ROOT, "Negative UDS response %02X/%02X", payload[1] & 255, payload[2] & 255));
        }
        if (payload.length < 4 || payload[0] != 0x62 || (payload[1] & 255) != (did >>> 8) ||
                (payload[2] & 255) != (did & 255)) throw bad("Wrong service or DID");
        return Arrays.copyOfRange(payload, 3, payload.length);
    }

    private static byte[] isotp(List<byte[]> frames) {
        byte[] first = frames.get(0);
        if (first.length < 2 || first.length > 8) throw bad("Invalid CAN frame size");
        int type = (first[0] & 255) >>> 4;
        if (type == 0) {
            int n = first[0] & 15;
            if (frames.size() != 1 || n == 0 || n > first.length - 1) throw bad("Bad single frame");
            return Arrays.copyOfRange(first, 1, 1 + n);
        }
        if (type != 1 || first.length != 8) throw bad("Missing first ISO-TP frame");
        int n = ((first[0] & 15) << 8) | (first[1] & 255);
        if (n <= 7 || n > 256) throw bad("Invalid ISO-TP total length");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(first, 2, first.length - 2);
        int seq = 1;
        for (int i = 1; i < frames.size(); i++) {
            byte[] f = frames.get(i);
            if (out.size() >= n) throw bad("Extra ISO-TP frame");
            if (f.length < 2 || f.length > 8 || (f[0] & 255) != (0x20 | (seq & 15))) throw bad("ISO-TP sequence mismatch");
            out.write(f, 1, f.length - 1); seq++;
        }
        if (out.size() < n) throw bad("Truncated ISO-TP reply");
        return Arrays.copyOf(out.toByteArray(), n);
    }

    private static byte[] hex(String s) {
        s = s.replaceAll("\\s", "");
        if (s.isEmpty() || s.length() % 2 != 0 || !s.matches("[0-9A-F]+")) throw bad("Non-hex data");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    public static String printable(byte[] bytes) {
        int end = bytes.length;
        while (end > 0 && (bytes[end-1] == 0 || (bytes[end-1] & 255) == 255)) end--;
        for (int i=0;i<end;i++) if ((bytes[i] & 255) < 32 || (bytes[i] & 255) > 126) return "HEX " + hexText(bytes);
        return new String(bytes, 0, end, StandardCharsets.US_ASCII).trim();
    }
    public static String hexText(byte[] bytes) {
        StringBuilder s = new StringBuilder();
        for (byte b : bytes) s.append(String.format(Locale.ROOT, "%02X", b & 255));
        return s.toString();
    }
    private static IllegalArgumentException bad(String why) { return new IllegalArgumentException(why); }
}
