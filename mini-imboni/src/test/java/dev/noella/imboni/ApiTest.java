package dev.noella.imboni;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Boots the real Spring app (in-memory stores, offline model) and drives it over HTTP. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiTest {

    @Autowired
    MockMvc mvc;

    final ObjectMapper json = new ObjectMapper();

    @Test
    void requiresApiKey() throws Exception {
        mvc.perform(get("/api/documents")).andExpect(status().isUnauthorized());
    }

    @Test
    void uploadGenerateReviewAndOps() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "newton.md", "text/markdown",
                ComponentTests.NEWTON.getBytes(StandardCharsets.UTF_8));
        String body = mvc.perform(multipart("/api/documents").file(file).header("X-API-Key", "dev-key-alice"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        String docId = json.readTree(body).get("id").asText();

        // Processing is asynchronous: poll until READY.
        String state = "";
        for (int i = 0; i < 50 && !state.equals("READY"); i++) {
            Thread.sleep(50);
            state = json.readTree(mvc.perform(get("/api/documents/" + docId).header("X-API-Key", "dev-key-alice"))
                    .andReturn().getResponse().getContentAsString()).get("status").asText();
        }
        assertThat(state).isEqualTo("READY");

        // Another teacher can't see it (and can't tell whether it exists).
        mvc.perform(get("/api/documents/" + docId).header("X-API-Key", "dev-key-bob")).andExpect(status().isNotFound());

        // Validation: grade level is required.
        mvc.perform(post("/api/lessons").header("X-API-Key", "dev-key-alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"topic\":\"forces\",\"durationMinutes\":45}")).andExpect(status().isBadRequest());

        String lesson = mvc.perform(post("/api/lessons").header("X-API-Key", "dev-key-alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"force mass and acceleration\",\"gradeLevel\":\"Grade 9\",\"durationMinutes\":45}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.citations[0].documentId").value(docId))
                .andReturn().getResponse().getContentAsString();
        JsonNode l = json.readTree(lesson);
        assertThat(l.get("status").asText()).isIn("AUTO_APPROVED", "NEEDS_REVIEW");

        // Refusal is a normal 200 response with an explanation.
        mvc.perform(post("/api/lessons").header("X-API-Key", "dev-key-alice").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"the French Revolution\",\"gradeLevel\":\"Grade 9\",\"durationMinutes\":45}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REFUSED"));

        mvc.perform(get("/api/ops/summary").header("X-API-Key", "dev-key-alice"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requests").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)));
    }

    @Test
    void reviewQueueApproveFlow() throws Exception {
        // A scanned (OCR) source → any lesson citing it must be reviewed → queue → approve.
        MockMultipartFile file = new MockMultipartFile("file", "water_scan.txt", "text/plain",
                ("<!-- source: scan -->\n" + ComponentTests.WATER).getBytes(StandardCharsets.UTF_8));
        String id = json.readTree(mvc.perform(multipart("/api/documents").file(file).header("X-API-Key", "dev-key-bob"))
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        for (int i = 0; i < 50; i++) {
            Thread.sleep(50);
            String st = json.readTree(mvc.perform(get("/api/documents/" + id).header("X-API-Key", "dev-key-bob"))
                    .andReturn().getResponse().getContentAsString()).get("status").asText();
            if (st.equals("READY")) break;
        }
        JsonNode r = json.readTree(mvc.perform(post("/api/lessons").header("X-API-Key", "dev-key-bob").contentType(MediaType.APPLICATION_JSON)
                .content("{\"topic\":\"evaporation and clouds\",\"gradeLevel\":\"Grade 5\",\"durationMinutes\":30}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("status").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(r.get("reviewReasons").toString()).contains("cites_flagged_source");
        String lessonId = r.get("lessonId").asText();
        mvc.perform(get("/api/reviews").header("X-API-Key", "dev-key-bob")).andExpect(jsonPath("$[0].id").value(lessonId));
        mvc.perform(get("/api/reviews").header("X-API-Key", "dev-key-alice")).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(post("/api/reviews/" + lessonId).header("X-API-Key", "dev-key-bob").contentType(MediaType.APPLICATION_JSON)
                .content("{\"approve\":true,\"note\":\"checked against textbook\"}")).andExpect(jsonPath("$.state").value("APPROVED"));
    }
}
