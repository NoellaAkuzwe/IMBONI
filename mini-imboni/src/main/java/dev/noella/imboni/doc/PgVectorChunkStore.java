package dev.noella.imboni.doc;

import dev.noella.imboni.text.Text;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL + pgvector implementation. Permission filtering is part of the WHERE clause
 * ({@code document_id = ANY(?)}), so the database never returns passages the caller can't read.
 */
@Component
@Profile("pg")
@DependsOn("pgSchema")
public class PgVectorChunkStore implements ChunkStore {

    private final JdbcTemplate jdbc;

    public PgVectorChunkStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String COLS =
            "id, document_id, owner_id, document_title, ordinal, section, page, text, warnings";

    private static final RowMapper<Chunk> MAPPER = (rs, i) -> {
        Array w = rs.getArray("warnings");
        return new Chunk(rs.getString("id"), rs.getString("document_id"), rs.getString("owner_id"),
                rs.getString("document_title"), rs.getInt("ordinal"), rs.getString("section"), rs.getInt("page"),
                rs.getString("text"), w == null ? List.of() : Arrays.asList((String[]) w.getArray()), null);
    };

    @Override
    @Transactional
    public void replaceChunks(String documentId, List<Chunk> chunks) {
        jdbc.update("DELETE FROM chunks WHERE document_id = ?", documentId);
        for (Chunk c : chunks) {
            jdbc.update(con -> {
                var ps = con.prepareStatement("INSERT INTO chunks (" + COLS + ", embedding) VALUES (?,?,?,?,?,?,?,?,?,?::vector)");
                ps.setString(1, c.id());
                ps.setString(2, c.documentId());
                ps.setString(3, c.ownerId());
                ps.setString(4, c.documentTitle());
                ps.setInt(5, c.ordinal());
                ps.setString(6, c.section());
                ps.setInt(7, c.page());
                ps.setString(8, c.text());
                ps.setArray(9, con.createArrayOf("text", c.warnings().toArray()));
                ps.setString(10, toVector(c.embedding()));
                return ps;
            });
        }
    }

    @Override
    public List<Chunk> chunksOf(String documentId) {
        return jdbc.query("SELECT " + COLS + " FROM chunks WHERE document_id = ? ORDER BY ordinal", MAPPER, documentId);
    }

    @Override
    public int countAll() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM chunks", Integer.class);
        return n == null ? 0 : n;
    }

    @Override
    public List<Scored> vectorSearch(float[] query, Collection<String> permittedDocIds, int k) {
        if (permittedDocIds.isEmpty()) return List.of();
        String v = toVector(query);
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT " + COLS + ", 1 - (embedding <=> ?::vector) AS score FROM chunks "
                    + "WHERE document_id = ANY(?) ORDER BY embedding <=> ?::vector LIMIT ?");
            ps.setString(1, v);
            ps.setArray(2, con.createArrayOf("text", permittedDocIds.toArray()));
            ps.setString(3, v);
            ps.setInt(4, k);
            return ps;
        }, (rs, i) -> new Scored(MAPPER.mapRow(rs, i), rs.getDouble("score")));
    }

    @Override
    public List<Scored> keywordSearch(String query, Collection<String> permittedDocIds, int k) {
        if (permittedDocIds.isEmpty()) return List.of();
        // OR the query words together: plainto_tsquery would AND them, which is too strict for
        // natural-language teacher requests.
        String tsq = Text.words(query).stream()
                .filter(w -> w.matches("[a-z0-9]+") && w.length() > 1)
                .distinct()
                .collect(Collectors.joining(" | "));
        if (tsq.isBlank()) return List.of();
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT " + COLS + ", ts_rank(tsv, q) AS score "
                    + "FROM chunks, to_tsquery('english', ?) q "
                    + "WHERE document_id = ANY(?) AND tsv @@ q ORDER BY score DESC LIMIT ?");
            ps.setString(1, tsq);
            ps.setArray(2, con.createArrayOf("text", permittedDocIds.toArray()));
            ps.setInt(3, k);
            return ps;
        }, (rs, i) -> new Scored(MAPPER.mapRow(rs, i), rs.getDouble("score")));
    }

    static String toVector(float[] e) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < e.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(e[i]);
        }
        return sb.append(']').toString();
    }
}
