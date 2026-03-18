CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS documents (
    id BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(20) NOT NULL,
    original_name TEXT NOT NULL,
    source_url TEXT,
    status VARCHAR(20) NOT NULL,
    transcript TEXT,
    error_message TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

ALTER TABLE documents
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_documents_status ON documents(status);
CREATE INDEX IF NOT EXISTS idx_documents_updated_at ON documents(updated_at);

CREATE TABLE IF NOT EXISTS document_summaries (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL UNIQUE,
    summary_json TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_document_summaries_document
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content TEXT NOT NULL,
    embedding VECTOR(1536) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index),
    CONSTRAINT fk_document_chunks_document
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_document_chunks_document_id ON document_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding_ivfflat
    ON document_chunks USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);
CREATE INDEX IF NOT EXISTS idx_document_chunks_content_fts
    ON document_chunks USING GIN (to_tsvector('simple', content));

CREATE TABLE IF NOT EXISTS rag_index_jobs (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP NOT NULL DEFAULT now(),
    chunk_count INT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT fk_rag_index_jobs_document
        FOREIGN KEY (document_id) REFERENCES documents(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_status_retry ON rag_index_jobs(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_rag_index_jobs_document_id ON rag_index_jobs(document_id);
