package dev.belegagent.extraction;

import java.util.Arrays;
import java.util.Optional;

/**
 * Supported input formats, detected from the file's magic bytes. The client-supplied
 * Content-Type and file name are never trusted.
 */
public enum FileKind {
    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp");

    private final String mimeType;
    private final String extension;

    FileKind(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public boolean isImage() {
        return this != PDF;
    }

    public static Optional<FileKind> detect(byte[] content) {
        if (content == null) {
            return Optional.empty();
        }
        if (startsWith(content, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(PDF);
        }
        if (startsWith(content, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(JPEG);
        }
        if (startsWith(content, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(PNG);
        }
        if (startsWith(content, 0, 'R', 'I', 'F', 'F') && startsWith(content, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    public static Optional<FileKind> fromMimeType(String mimeType) {
        return Arrays.stream(values()).filter(k -> k.mimeType.equals(mimeType)).findFirst();
    }

    private static boolean startsWith(byte[] content, int offset, int... signature) {
        if (content.length < offset + signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((content[offset + i] & 0xFF) != signature[i]) {
                return false;
            }
        }
        return true;
    }
}
