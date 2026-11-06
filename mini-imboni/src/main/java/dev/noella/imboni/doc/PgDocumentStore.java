package dev.noella.imboni.doc;

import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@Component
@Profile("pg")
@DependsOn("pgSchema")
public class PgDocumentStore implements DocumentStore {

    private final JdbcTemplate jdbc;

    public PgDocumentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Document> MAPPER = (rs, i) -> {
        Array w = rs.getArray("warnings");
        return new Document(
                rs.getString("id"), rs.getString("owner_id"), rs.getString("title"), rs.getString("filename"),
                rs.getString("content_hash"), DocumentStatus.valueOf(rs.getString("status")), rs.getString("error"),
                w == null ? List.of() : Arrays.asList((String[]) w.getArray()),
                rs.getInt("chunk_count"), rs.getInt("attempts"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    };

    @Override
    public Document save(Document d) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO documents (id, owner_id, title, filename, content_hash, status, error, warnings,
                                           chunk_count, attempts, created_at, updated_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    ON CONFLICT (id) DO UPDATE SET status = EXCLUDED.status, error = EXCLUDED.error,
                        warnings = EXCLUDED.warnings, chunk_count = EXCLUDED.chunk_count,
                        attempts = EXCLUDED.attempts, updated_at = EXCLUDED.updated_at""");
            ps.setString(1, d.id());
            ps.setString(2, d.ownerId());
            ps.setString(3, d.title());
            ps.setString(4, d.filename());
            ps.setString(5, d.contentHash());
            ps.setString(6, d.status().name());
            ps.setString(7, d.error());
            ps.setArray(8, con.createArrayOf("text", d.extractionWarnings().toArray()));
            ps.setInt(9, d.chunkCount());
            ps.setInt(10, d.attempts());
            ps.setTimestamp(11, Timestamp.from(d.createdAt()));
            ps.setTimestamp(12, Timestamp.from(d.updatedAt()));
            return ps;
        });
        return d;
    }

    @Override
    public Optional<Document> findForOwner(String id, String ownerId) {
        return jdbc.query("SELECT * FROM documents WHERE id = ? AND owner_id = ?", MAPPER, id, ownerId)
                .stream().findFirst();
    }

    @Override
    public Optional<Document> findById(String id) {
        return jdbc.query("SELECT * FROM documents WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    @Override
    public Optional<Document> findByOwnerAndHash(String ownerId, String contentHash) {
        return jdbc.query("SELECT * FROM documents WHERE owner_id = ? AND content_hash = ?", MAPPER, ownerId, contentHash)
                .stream().findFirst();
    }

    @Override
    public List<Document> listForOwner(String ownerId) {
        return jdbc.query("SELECT * FROM documents WHERE owner_id = ? ORDER BY created_at", MAPPER, ownerId);
    }
}
