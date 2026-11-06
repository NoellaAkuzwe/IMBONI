package dev.noella.imboni.doc;

import dev.noella.imboni.config.ImboniProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Creates the Postgres schema. Done in code (not schema.sql) because the vector column's dimension
 * depends on which embedding model is configured. A real deployment would use Flyway migrations.
 */
@Component
@Profile("pg")
public class PgSchema {

    private final JdbcTemplate jdbc;
    private final ImboniProperties props;

    public PgSchema(JdbcTemplate jdbc, ImboniProperties props) {
        this.jdbc = jdbc;
        this.props = props;
    }

    @PostConstruct
    void init() {
        int dim = props.embedding().dimensions();
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS documents (
                    id            text PRIMARY KEY,
                    owner_id      text NOT NULL,
                    title         text NOT NULL,
                    filename      text NOT NULL,
                    content_hash  text NOT NULL,
                    status        text NOT NULL,
                    error         text,
                    warnings      text[] NOT NULL DEFAULT '{}',
                    chunk_count   int NOT NULL DEFAULT 0,
                    attempts      int NOT NULL DEFAULT 0,
                    created_at    timestamptz NOT NULL,
                    updated_at    timestamptz NOT NULL,
                    UNIQUE (owner_id, content_hash)
                )""");
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS chunks (
                    id            text PRIMARY KEY,
                    document_id   text NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
                    owner_id      text NOT NULL,
                    document_title text NOT NULL,
                    ordinal       int NOT NULL,
                    section       text,
                    page          int NOT NULL,
                    text          text NOT NULL,
                    warnings      text[] NOT NULL DEFAULT '{}',
                    embedding     vector(%d) NOT NULL,
                    tsv           tsvector GENERATED ALWAYS AS
                                  (to_tsvector('english', coalesce(section,'') || ' ' || text)) STORED,
                    UNIQUE (document_id, ordinal)
                )""".formatted(dim));
        jdbc.execute("CREATE INDEX IF NOT EXISTS chunks_doc_idx ON chunks(document_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS chunks_tsv_idx ON chunks USING gin(tsv)");
        // HNSW index for approximate nearest neighbour search (pgvector >= 0.5).
        jdbc.execute("CREATE INDEX IF NOT EXISTS chunks_embedding_idx ON chunks USING hnsw (embedding vector_cosine_ops)");
    }
}
