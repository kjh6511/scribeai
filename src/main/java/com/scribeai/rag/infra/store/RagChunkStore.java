package com.scribeai.rag.infra.store;

import com.scribeai.rag.application.model.RagSearchHit;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class RagChunkStore {

    private final JdbcTemplate jdbcTemplate;

    // 작업 청크 삭제
    public void deleteByDocumentId(Long documentId) {
        jdbcTemplate.update("DELETE FROM document_chunks WHERE document_id = ?", documentId);
    }

    // 작업 청크 개수 조회
    public int countByDocumentId(Long documentId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM document_chunks WHERE document_id = ?",
                Integer.class,
                documentId
        );
        return count == null ? 0 : count;
    }

    // 작업 청크 저장
    public void saveAll(Long documentId, List<String> chunks, List<float[]> embeddings) {
        if (chunks.isEmpty()) {
            return;
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO document_chunks (document_id, chunk_index, content, embedding) VALUES (?, ?, ?, CAST(? AS vector))",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setLong(1, documentId);
                        ps.setInt(2, i);
                        ps.setString(3, chunks.get(i));
                        ps.setString(4, toVectorLiteral(embeddings.get(i)));
                    }

                    @Override
                    public int getBatchSize() {
                        return chunks.size();
                    }
                }
        );
    }

    // 유사 청크 조회
    public List<RagSearchHit> searchVectorByDocumentId(Long documentId, float[] queryEmbedding, int topK) {
        String queryVector = toVectorLiteral(queryEmbedding);
        return jdbcTemplate.query(
                "SELECT chunk_index, content, (1 - (embedding <=> CAST(? AS vector))) AS score "
                        + "FROM document_chunks "
                        + "WHERE document_id = ? "
                        + "ORDER BY embedding <=> CAST(? AS vector) "
                        + "LIMIT ?",
                (rs, rowNum) -> new RagSearchHit(
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("score")
                ),
                queryVector,
                documentId,
                queryVector,
                topK
        );
    }

    // 키워드 기반 청크 조회
    public List<RagSearchHit> searchKeywordByDocumentId(Long documentId, String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        return jdbcTemplate.query(
                """
                SELECT chunk_index, content,
                       ts_rank_cd(to_tsvector('simple', content), plainto_tsquery('simple', ?)) AS score
                FROM document_chunks
                WHERE document_id = ?
                  AND to_tsvector('simple', content) @@ plainto_tsquery('simple', ?)
                ORDER BY score DESC, chunk_index ASC
                LIMIT ?
                """,
                (rs, rowNum) -> new RagSearchHit(
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("score")
                ),
                query,
                documentId,
                query,
                topK
        );
    }

    // 하위 호환: 기존 벡터 검색 메서드 이름
    public List<RagSearchHit> searchByDocumentId(Long documentId, float[] queryEmbedding, int topK) {
        return searchVectorByDocumentId(documentId, queryEmbedding, topK);
    }

    // 작업 청크 순서 조회
    public List<RagSearchHit> findByDocumentIdOrderByChunkIndex(Long documentId, int limit) {
        return jdbcTemplate.query(
                "SELECT chunk_index, content, 0.0 AS score FROM document_chunks WHERE document_id = ? ORDER BY chunk_index LIMIT ?",
                (rs, rowNum) -> new RagSearchHit(
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        rs.getDouble("score")
                ),
                documentId,
                limit
        );
    }

    // TTL 지난 청크 삭제
    public int deleteOlderThanMinutes(int retentionMinutes) {
        return jdbcTemplate.update(
                "DELETE FROM document_chunks WHERE created_at < (now() - (? * interval '1 minute'))",
                retentionMinutes
        );
    }

    private String toVectorLiteral(float[] vector) {
        List<String> values = new ArrayList<>(vector.length);
        for (float value : vector) {
            values.add(Float.toString(value));
        }
        return "[" + String.join(",", values) + "]";
    }
}
