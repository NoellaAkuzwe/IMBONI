package dev.noella.imboni;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Same flow against real PostgreSQL + pgvector. Opt-in:
 * <pre>docker compose up -d && PG_TESTS=1 mvn test -Dtest=PgIntegrationTest</pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("pg")
@EnabledIfEnvironmentVariable(named = "PG_TESTS", matches = "1")
class PgIntegrationTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    final ObjectMapper json = new ObjectMapper();

    String upload(String key, String name, String content) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", name, "text/markdown", content.getBytes(StandardCharsets.UTF_8));
        String id = json.readTree(mvc.perform(multipart("/api/documents").file(f).header("X-API-Key", key))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        for (int i = 0; i < 100; i++) {
            Thread.sleep(50);
            String st = json.readTree(mvc.perform(get("/api/documents/" + id).header("X-API-Key", key))
                    .andReturn().getResponse().getContentAsString()).get("status").asText();
            if (st.equals("READY")) return id;
            if (st.equals("FAILED")) throw new AssertionError("ingestion failed");
        }
        throw new AssertionError("timeout");
    }

    @Test
    void endToEndOnPgvector() throws Exception {
        // Unique content per run so the dedupe-by-hash doesn't return rows from a previous run.
        String salt = "\n\nRun " + UUID.randomUUID() + ".";
        String alice = upload("dev-key-alice", "newton.md", ComponentTests.NEWTON + salt);
        String bob = upload("dev-key-bob", "water.md", ComponentTests.WATER + salt);

        Integer n = jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, alice);
        assertThat(n).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT owner_id FROM chunks WHERE document_id = ? LIMIT 1", String.class, alice)).isEqualTo("alice");

        JsonNode r = json.readTree(mvc.perform(post("/api/lessons").header("X-API-Key", "dev-key-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"force mass and acceleration\",\"gradeLevel\":\"Grade 9\",\"durationMinutes\":45}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(r.get("status").asText()).isIn("AUTO_APPROVED", "NEEDS_REVIEW");
        assertThat(r.get("citations")).isNotEmpty();
        // The database is persistent across runs, so check ownership rather than one specific document.
        r.get("citations").forEach(c -> assertThat(jdbc.queryForObject("SELECT owner_id FROM documents WHERE id = ?",
                String.class, c.get("documentId").asText())).isEqualTo("alice"));

        // Bob's water-cycle document must never answer Alice.
        JsonNode refused = json.readTree(mvc.perform(post("/api/lessons").header("X-API-Key", "dev-key-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"evaporation and clouds\",\"gradeLevel\":\"Grade 5\",\"durationMinutes\":45,\"documentIds\":[]}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(refused.get("citations").toString()).doesNotContain(bob);

        // Re-uploading the same file returns the same document and doesn't add rows.
        String again = upload("dev-key-alice", "newton.md", ComponentTests.NEWTON + salt);
        assertThat(again).isEqualTo(alice);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chunks WHERE document_id = ?", Integer.class, alice)).isEqualTo(n);
    }
}
