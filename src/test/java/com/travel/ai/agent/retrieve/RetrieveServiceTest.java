package com.travel.ai.agent.retrieve;

import com.travel.ai.agent.QueryRewriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrieveServiceTest {

    @Test
    void queryRewriteIsCalledAndReturnedQueriesArePreserved() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(rewriter.rewrite("分析苹果财报")).thenReturn(List.of("q1", "q2"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RetrieveService service = new RetrieveService(rewriter, vectorStore);

        RetrieveResult result = service.retrieve(request("分析苹果财报", "alice"));

        verify(rewriter).rewrite("分析苹果财报");
        assertThat(result.queries()).containsExactly("q1", "q2");
    }

    @Test
    void vectorSearchUsesUserIdFilter() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(rewriter.rewrite("question")).thenReturn(List.of("q1", "q2"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RetrieveService service = new RetrieveService(rewriter, vectorStore);

        service.retrieve(request("question", "user-123"));

        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        verify(vectorStore, times(2)).similaritySearch(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(SearchRequest::getQuery)
                .containsExactly("q1", "q2");
        assertThat(captor.getAllValues())
                .extracting(SearchRequest::getTopK)
                .containsOnly(2);
        assertThat(captor.getAllValues())
                .extracting(SearchRequest::getSimilarityThreshold)
                .containsOnly(0.5);
        for (SearchRequest searchRequest : captor.getAllValues()) {
            Filter.Expression expression = searchRequest.getFilterExpression();
            assertThat(expression.type()).isEqualTo(Filter.ExpressionType.EQ);
            assertThat(expression.left()).isEqualTo(new Filter.Key("user_id"));
            assertThat(expression.right()).isEqualTo(new Filter.Value("user-123"));
        }
    }

    @Test
    void mergeDedupeKeepsFirstSeenOrder() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        VectorStore vectorStore = mock(VectorStore.class);
        Document first = doc("doc-1", "first", "source-a");
        Document second = doc("doc-2", "second", "source-b");
        Document duplicateFirst = doc("doc-1", "duplicate", "source-c");
        Document third = doc("doc-3", "third", "source-d");
        when(rewriter.rewrite("question")).thenReturn(List.of("q1", "q2"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(first, second))
                .thenReturn(List.of(duplicateFirst, third));
        RetrieveService service = new RetrieveService(rewriter, vectorStore);

        RetrieveResult result = service.retrieve(request("question", "alice"));

        assertThat(result.docs()).containsExactly(first, second, third);
    }

    @Test
    void emptyHitUsesOriginalUserQuestionAsPromptBase() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(rewriter.rewrite("unmatched")).thenReturn(List.of("q1"));
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        RetrieveService service = new RetrieveService(rewriter, vectorStore);

        RetrieveResult result = service.retrieve(request("unmatched", "alice"));

        assertThat(result.docs()).isEmpty();
        assertThat(result.promptBase()).isEqualTo("unmatched");
        assertThat(result.citationBlock()).isEqualTo("【引用片段】\n（本轮未命中知识库）\n\n");
    }

    @Test
    void hitDocsBuildResearchPromptBase() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(rewriter.rewrite("question")).thenReturn(List.of("q1"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("doc-1", "alpha", "source-a"), doc("doc-2", "beta", "source-b")));
        RetrieveService service = new RetrieveService(rewriter, vectorStore);

        RetrieveResult result = service.retrieve(request("question", "alice"));

        assertThat(result.promptBase()).isEqualTo("""
                【研究参考资料】
                alpha
                beta

                【用户问题】
                question""");
    }

    @Test
    void citationBlockFormatIsCompatible() {
        QueryRewriter rewriter = mock(QueryRewriter.class);
        VectorStore vectorStore = mock(VectorStore.class);
        when(rewriter.rewrite("question")).thenReturn(List.of("q1"));
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(doc("doc-1", "alpha", "source-a"), doc("doc-2", "beta", "source-b")));
        RetrieveService service = new RetrieveService(rewriter, vectorStore);

        RetrieveResult result = service.retrieve(request("question", "alice"));

        assertThat(result.citationBlock()).isEqualTo("""
                【引用片段】（共 2 条）
                [1] id=doc-1 来源=source-a
                alpha

                [2] id=doc-2 来源=source-b
                beta

                ────────
                """);
    }

    private static RetrieveRequest request(String userMessage, String currentUser) {
        return new RetrieveRequest(userMessage, currentUser, "req-retrieve", 5, 2, 0.5);
    }

    private static Document doc(String id, String text, String sourceName) {
        return new Document(id, text, Map.of("source_name", sourceName));
    }
}
