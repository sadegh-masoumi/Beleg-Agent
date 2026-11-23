package dev.belegagent.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileKindTest {

    @Test
    void detectsPdf() {
        assertThat(FileKind.detect("%PDF-1.7\n...".getBytes(StandardCharsets.US_ASCII))).contains(FileKind.PDF);
    }

    @Test
    void detectsJpeg() {
        assertThat(FileKind.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10))).contains(FileKind.JPEG);
    }

    @Test
    void detectsPng() {
        assertThat(FileKind.detect(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0))).contains(FileKind.PNG);
    }

    @Test
    void detectsWebp() {
        assertThat(FileKind.detect(bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'))).contains(FileKind.WEBP);
    }

    @Test
    void rejectsUnknownAndTinyFiles() {
        assertThat(FileKind.detect("hello".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(FileKind.detect(new byte[0])).isEmpty();
        assertThat(FileKind.detect(null)).isEmpty();
    }

    private static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }
}
