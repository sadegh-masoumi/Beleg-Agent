package dev.belegagent.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.belegagent.app.storage.DocumentStorage;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** API role: upload, idempotency, tenant isolation, validation. No worker, no model. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class DocumentApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    DocumentStorage storage;

    @Autowired
    JdbcClient jdbc;

    @Test
    void uploadStoresFileAndRegistersDocument() throws Exception {
        byte[] png = png("upload");

        String body = mvc.perform(multipart("/api/documents").file(file("bon.png", png)).header("X-Tenant-Id", "t-a"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("RECEIVED"))
                .andExpect(jsonPath("$.contentType").value("image/png"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/documents/{id}", id).header("X-Tenant-Id", "t-a"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("bon.png"));

        String objectKey = jdbc.sql("SELECT object_key FROM document WHERE id = CAST(:id AS uuid)")
                .param("id", id)
                .query(String.class)
                .single();
        assertThat(objectKey).startsWith("t-a/").endsWith(id + ".png");
        assertThat(storage.get(objectKey)).isEqualTo(png);
    }

    @Test
    void sameFileTwiceIsNotProcessedAgain() throws Exception {
        byte[] png = png("duplicate");
        String first = mvc.perform(multipart("/api/documents").file(file("a.png", png)).header("X-Tenant-Id", "t-b"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String second = mvc.perform(multipart("/api/documents").file(file("b.png", png)).header("X-Tenant-Id", "t-b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(second, "$.id")).isEqualTo(JsonPath.read(first, "$.id"));
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        byte[] png = png("tenants");
        String body = mvc.perform(multipart("/api/documents").file(file("x.png", png)).header("X-Tenant-Id", "t-c"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        mvc.perform(get("/api/documents/{id}", id).header("X-Tenant-Id", "t-other"))
                .andExpect(status().isNotFound());
        // same file for another tenant is a new document
        mvc.perform(multipart("/api/documents").file(file("x.png", png)).header("X-Tenant-Id", "t-other"))
                .andExpect(status().isCreated());
    }

    @Test
    void rejectsUnsupportedFilesRegardlessOfDeclaredType() throws Exception {
        MockMultipartFile fake = new MockMultipartFile("file", "bon.png", "image/png",
                "not really a png".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/documents").file(fake))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void rejectsInvalidTenantId() throws Exception {
        mvc.perform(multipart("/api/documents").file(file("a.png", png("tenant"))).header("X-Tenant-Id", "../etc"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void extractionIsNotFoundBeforeProcessing() throws Exception {
        String body = mvc.perform(multipart("/api/documents").file(file("y.png", png("pending"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/documents/{id}/extraction", (String) JsonPath.read(body, "$.id")))
                .andExpect(status().isNotFound());
    }

    static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/octet-stream", content);
    }

    /** PNG signature followed by a unique payload; intake checks the signature only. */
    static byte[] png(String payload) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] rest = payload.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[signature.length + rest.length];
        System.arraycopy(signature, 0, out, 0, signature.length);
        System.arraycopy(rest, 0, out, signature.length, rest.length);
        return out;
    }
}
