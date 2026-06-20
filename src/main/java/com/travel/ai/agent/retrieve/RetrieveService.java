package com.travel.ai.agent.retrieve;

import com.travel.ai.agent.QueryRewriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG retrieval service for the finance analyst mainline.
 *
 * <p>This service does not mutate TravelAgent turn state, does not emit SSE,
 * does not write Redis, and does not generate policy events.</p>
 */
public final class RetrieveService {

    private static final Logger log = LoggerFactory.getLogger(RetrieveService.class);

    private final QueryRewriter queryRewriter;
    private final VectorStore vectorStore;

    public RetrieveService(QueryRewriter queryRewriter, VectorStore vectorStore) {
        this.queryRewriter = queryRewriter;
        this.vectorStore = vectorStore;
    }

    public RetrieveResult retrieve(RetrieveRequest request) {
        String userMessage = request != null && request.userMessage() != null ? request.userMessage() : "";
        String currentUser = request != null && request.currentUser() != null && !request.currentUser().isBlank()
                ? request.currentUser()
                : "anonymous";
        int maxContextDocs = request != null ? request.maxContextDocs() : 0;
        int topKPerQuery = request != null ? request.topKPerQuery() : 0;
        double similarityThreshold = request != null ? request.similarityThreshold() : 0.0;

        long tRewrite0 = System.nanoTime();
        List<String> queries = queryRewriter.rewrite(userMessage);
        long rewriteMs = (System.nanoTime() - tRewrite0) / 1_000_000L;

        Filter.Expression userFilter = new Filter.Expression(
                Filter.ExpressionType.EQ,
                new Filter.Key("user_id"),
                new Filter.Value(currentUser)
        );

        long tRetrieve0 = System.nanoTime();
        List<Document> flat = queries.stream()
                .flatMap(query -> vectorStore.similaritySearch(
                        SearchRequest.builder()
                                .query(query)
                                .topK(topKPerQuery)
                                .similarityThreshold(similarityThreshold)
                                .filterExpression(userFilter)
                                .build()
                ).stream())
                .collect(Collectors.toList());
        List<Document> docs = mergeAndDedupeDocuments(flat, maxContextDocs);
        long retrieveMs = (System.nanoTime() - tRetrieve0) / 1_000_000L;

        log.info("检索到 {} 条知识，queries={}", docs.size(), queries);
        log.info("[perf] rewrite_ms={} retrieve_ms={} doc_count={} requestId={}",
                rewriteMs, retrieveMs, docs.size(), request != null ? request.requestId() : null);

        String context = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n"));

        String promptBase = context.isEmpty()
                ? userMessage
                : "【研究参考资料】\n" + context + "\n\n【用户问题】\n" + userMessage;

        return new RetrieveResult(
                currentUser,
                queries,
                docs,
                promptBase,
                buildCitationBlock(docs),
                rewriteMs,
                retrieveMs
        );
    }

    /**
     * 多路检索结果合并去重：优先用 {@link Document#getId()} 作为稳定键；无 id 时用正文 hash，避免同一段文本重复进入上下文。
     * 使用 LinkedHashMap 保持「首次出现」顺序，便于与检索先后大致对应。
     */
    static List<Document> mergeAndDedupeDocuments(List<Document> documents, int maxDocs) {
        Map<String, Document> seen = new LinkedHashMap<>();
        if (documents != null) {
            for (Document d : documents) {
                if (d == null) {
                    continue;
                }
                String key;
                if (d.getId() != null && !d.getId().isBlank()) {
                    key = "id:" + d.getId();
                } else {
                    String text = d.getText() != null ? d.getText() : "";
                    key = "text:" + text.hashCode();
                }
                seen.putIfAbsent(key, d);
            }
        }
        return new ArrayList<>(seen.values()).subList(0, Math.min(Math.max(0, maxDocs), seen.size()));
    }

    /**
     * 将本轮用于拼 prompt 的检索结果，以纯文本块形式前置输出（SSE 首段 data）。
     */
    static String buildCitationBlock(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "【引用片段】\n（本轮未命中知识库）\n\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【引用片段】（共 ").append(docs.size()).append(" 条）\n");
        int i = 1;
        for (Document d : docs) {
            String id = d.getId() != null ? d.getId() : "(无id)";
            Object src = d.getMetadata() != null ? d.getMetadata().get("source_name") : null;
            String preview = d.getText() != null ? d.getText() : "";
            if (preview.length() > 200) {
                preview = preview.substring(0, 200) + "…";
            }
            sb.append("[").append(i++).append("] id=").append(id);
            if (src != null) {
                sb.append(" 来源=").append(src);
            }
            sb.append("\n").append(preview).append("\n\n");
        }
        sb.append("────────\n");
        return sb.toString();
    }
}
