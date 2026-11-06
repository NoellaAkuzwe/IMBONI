package dev.noella.imboni.doc;

import java.time.Instant;
import java.util.List;

/**
 * Metadata for an uploaded teaching document. The raw bytes are kept only until processing
 * finishes; what the rest of the system uses are the chunks.
 */
public record Document(
        String id,
        String ownerId,
        String title,
        String filename,
        String contentHash,
        DocumentStatus status,
        String error,
        List<String> extractionWarnings,
        int chunkCount,
        int attempts,
        Instant createdAt,
        Instant updatedAt) {

    public Document {
        extractionWarnings = extractionWarnings == null ? List.of() : List.copyOf(extractionWarnings);
    }

    public Document withStatus(DocumentStatus s, String err) {
        return new Document(id, ownerId, title, filename, contentHash, s, err, extractionWarnings,
                chunkCount, attempts, createdAt, Instant.now());
    }

    public Document startedAttempt() {
        return new Document(id, ownerId, title, filename, contentHash, DocumentStatus.PROCESSING, null,
                extractionWarnings, chunkCount, attempts + 1, createdAt, Instant.now());
    }

    public Document ready(List<String> warnings, int chunks) {
        return new Document(id, ownerId, title, filename, contentHash, DocumentStatus.READY, null, warnings,
                chunks, attempts, createdAt, Instant.now());
    }
}
