package com.fiskerwarm.internet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Bounded content decoding; the file picker MIME type is not a trust boundary. */
public final class PrivateConfigFile {
    private PrivateConfigFile() {}
    public static final int LIMIT = 20000;

    public static String read(InputStream input) throws IOException {
        if (input == null) throw new IOException("NO_DOCUMENT");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int size;
        while ((size = input.read(buffer)) != -1) {
            if (bytes.size() + size > LIMIT) throw new IOException("CONFIG_LIMIT");
            bytes.write(buffer, 0, size);
        }
        String raw = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        // Some download/file providers preserve a UTF-8 BOM before the JSON object.
        return raw.startsWith("\ufeff") ? raw.substring(1) : raw;
    }
}
