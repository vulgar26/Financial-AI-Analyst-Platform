package com.travel.ai.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgvector.PGvector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class PgVectorStore implements VectorStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorStore.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public PgVectorStore(JdbcTemplate jdbcTemplate, EmbeddingModel embeddingModel, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public void add(List<Document> documents) {
        for (Document doc : documents) {
            float[] embedding = embeddingModel.embed(doc.getText());
            String metaJson = metadataJson(doc.getMetadata());
            UUID rowId;
            try {
                rowId = doc.getId() != null && !doc.getId().isBlank()
                        ? UUID.fromString(doc.getId())
                        : UUID.randomUUID();
            } catch (IllegalArgumentException ex) {
                rowId = UUID.randomUUID();
            }

            UUID finalRowId = rowId;
            jdbcTemplate.execute((Connection conn) -> {
                PGvector.addVectorType(conn);
                var ps = conn.prepareStatement(
                        "INSERT INTO vector_store (id, content, metadata, embedding) VALUES (?, ?, ?::json, ?)"
                );
                ps.setObject(1, finalRowId);
                ps.setString(2, doc.getText());
                ps.setString(3, metaJson);
                ps.setObject(4, new PGvector(embedding));
                ps.executeUpdate();
                ps.close();
                return null;
            });
        }
        log.info("存入向量库：{} 条", documents.size());
    }

    private String metadataJson(Map<String, Object> metadata) {
        try {
            if (metadata == null || metadata.isEmpty()) {
                return "{}";
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            log.warn("metadata 序列化失败，写入空对象: {}", e.getMessage());
            return "{}";
        }
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        float[] queryEmbedding = embeddingModel.embed(request.getQuery());
        Optional<String> userIdEq = extractEqUserId(request.getFilterExpression());

        return jdbcTemplate.execute((Connection conn) -> {
            PGvector.addVectorType(conn);
            String sql = "SELECT id::text AS id, content, metadata::text AS metadata FROM vector_store ";
            if (userIdEq.isPresent()) {
                sql += "WHERE metadata->>'user_id' = ? ";
            }
            sql += "ORDER BY embedding <=> ? LIMIT ?";

            var ps = conn.prepareStatement(sql);
            int idx = 1;
            if (userIdEq.isPresent()) {
                ps.setString(idx++, userIdEq.get());
            }
            ps.setObject(idx++, new PGvector(queryEmbedding));
            ps.setInt(idx, request.getTopK());

            var rs = ps.executeQuery();
            var results = new ArrayList<Document>();
            while (rs.next()) {
                String id = rs.getString("id");
                String content = rs.getString("content");
                String metaStr = rs.getString("metadata");
                Map<String, Object> meta = parseMetadata(metaStr);
                results.add(new Document(id, content, meta));
            }
            rs.close();
            ps.close();
            return results;
        });
    }

    private Map<String, Object> parseMetadata(String metaStr) {
        if (metaStr == null || metaStr.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(metaStr, MAP_TYPE);
        } catch (JsonProcessingException e) {
            log.debug("metadata 反序列化失败，使用空 Map: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * 仅支持 TravelAgent 使用的 {@code user_id == ?} 过滤；其它表达式保持不筛选（与旧行为一致）。
     */
    // Supports the finance analyst runtime user_id filter used by the legacy-named TravelAgent implementation.
    private Optional<String> extractEqUserId(Filter.Expression expr) {
        if (expr == null) {
            return Optional.empty();
        }
        if (expr.type() != Filter.ExpressionType.EQ) {
            return Optional.empty();
        }
        if (!(expr.left() instanceof Filter.Key key)) {
            return Optional.empty();
        }
        if (!"user_id".equals(key.key())) {
            return Optional.empty();
        }
        if (!(expr.right() instanceof Filter.Value val)) {
            return Optional.empty();
        }
        return Optional.of(String.valueOf(val.value()));
    }

    @Override
    public void delete(List<String> idList) {
        for (String id : idList) {
            jdbcTemplate.update("DELETE FROM vector_store WHERE id = ?::uuid", id);
        }
    }

    @Override
    public void delete(Filter.Expression filterExpression) {

    }
}
