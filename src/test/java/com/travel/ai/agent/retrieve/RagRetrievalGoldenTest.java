package com.travel.ai.agent.retrieve;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.travel.ai.agent.QueryRewriter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.document.Document;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 检索链路的<strong>端到端金标准评测</strong>（两层 eval 的语义质量层 / Phase 3 「C」）。
 *
 * <p>这是「85% 检索准确率怎么测出来的」的正面回答的第一块：让一批金融问题<em>真正流过</em>
 * 真实 embedding 检索（DashScope text-embedding-v3）→ 余弦相似度召回，用<em>已知答案</em>的金标准
 * 断言「检索是否召回了正确的公司语料」，并给出可复现、可 case 归因的准确率。</p>
 *
 * <h2>这一刀刻意只测什么、不测什么</h2>
 * <ul>
 *   <li><strong>被测变量 = embedding 检索质量本身</strong>：问 X 公司的问题，向量召回回来的 top 文档
 *       是不是 X 公司的语料。这是 RAG 的核心暗坑「召回了≠召回对了」最底层的那一环。</li>
 *   <li><strong>刻意隔离掉的变量</strong>：① 用 identity 改写器（原问题直接当 query），不引入 chat-LLM 改写这个
 *       会抖动的额外变量——改写质量是 {@link QueryRewriter} 自己该测的事；② 用内存向量库
 *       {@link SimpleVectorStore}（embedding 是<em>真的</em>，只是存内存做余弦），不牵扯 PgVector 存储正确性
 *       （那是集成测试的活）。一刀只解决一个问题。</li>
 * </ul>
 *
 * <h2>为什么不进 CI 硬门禁</h2>
 * <p>真 embedding 需联网、消耗额度、有抖动；用 {@link EnabledIfEnvironmentVariable} 仅在本机配置了
 * {@code SPRING_AI_DASHSCOPE_API_KEY} 时运行，CI 无 key → 自动跳过，既不失败也不计费。
 * 这正是两层 eval「确定性契约进门禁、语义质量离线手动」分工在 RAG 检索上的落地。</p>
 *
 * <p>断言用「金标准命中率阈值」而非「每条都对」，容忍向量检索在边缘问题上的少量召回偏差。</p>
 */
@EnabledIfEnvironmentVariable(named = "SPRING_AI_DASHSCOPE_API_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RagRetrievalGoldenTest {

    /** 金标准命中率下限：允许个别边缘问题召回偏差，但整体必须达标。 */
    private static final double MIN_ACCURACY = 0.8;

    /** 评测租户：与种子语料 user_id 对齐（RetrieveService 强制按 user_id 过滤）。 */
    private static final String EVAL_USER = "eval";

    private RetrieveService retrieveService;

    /**
     * 一条金标准样本：金融问题 + 期望召回到的公司语料 source_name。
     */
    private record GoldenCase(String name, String question, String expectedSourceName) {
    }

    @BeforeAll
    void setUp() {
        // 1) 真实 embedding 模型（DashScope text-embedding-v3，默认）
        DashScopeApi api = DashScopeApi.builder()
                .apiKey(System.getenv("SPRING_AI_DASHSCOPE_API_KEY"))
                .build();
        DashScopeEmbeddingModel embeddingModel = new DashScopeEmbeddingModel(api);

        // 2) 内存向量库：embedding 是真的，只是存内存做余弦相似度（不牵扯 PgVector 存储）
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(embeddingModel).build();
        vectorStore.add(seedCorpus());

        // 3) identity 改写器：原问题直接当 query，隔离掉 chat-LLM 改写这个抖动变量
        QueryRewriter identityRewriter = new IdentityQueryRewriter();
        this.retrieveService = new RetrieveService(identityRewriter, vectorStore);
    }

    @Test
    void retrievalRecallsCorrectCompany_aboveAccuracyThreshold() {
        List<GoldenCase> cases = goldenCases();

        int correct = 0;
        StringBuilder misses = new StringBuilder();
        for (GoldenCase c : cases) {
            RetrieveRequest req = new RetrieveRequest(
                    c.question(),
                    EVAL_USER,
                    "golden-" + c.name(),
                    3,      // maxContextDocs
                    3,      // topKPerQuery
                    0.0     // similarityThreshold：金标准只看「最相关的是否对」，不在此考阈值过滤
            );
            RetrieveResult result = retrieveService.retrieve(req);

            String topSource = result.docs().isEmpty()
                    ? "(empty)"
                    : String.valueOf(result.docs().get(0).getMetadata().get("source_name"));

            if (c.expectedSourceName().equals(topSource)) {
                correct++;
            } else {
                misses.append("\n  - ").append(c.name())
                        .append(" expected top source=").append(c.expectedSourceName())
                        .append(" but got=").append(topSource)
                        .append(" (docs=").append(result.docs().size()).append(')');
            }
        }

        double accuracy = (double) correct / cases.size();
        assertThat(accuracy)
                .as("RAG retrieval golden accuracy %d/%d; misses:%s", correct, cases.size(), misses)
                .isGreaterThanOrEqualTo(MIN_ACCURACY);
    }

    /**
     * 金标准问题集：每条问题指向唯一一家公司，期望向量检索 top1 召回该公司语料。
     * 问法刻意与语料措辞不同（不是关键词匹配），考的是 embedding 的语义召回。
     */
    private static List<GoldenCase> goldenCases() {
        return List.of(
                new GoldenCase("maotai_margin", "白酒龙头的毛利率和护城河怎么样", "贵州茅台-600519"),
                new GoldenCase("catl_battery", "国内动力电池行业的龙头是谁，壁垒在哪", "宁德时代-300750"),
                new GoldenCase("cmb_retail", "哪家银行以零售业务和高净息差见长", "招商银行-600036"),
                new GoldenCase("midea_appliance", "空调和白色家电的龙头公司海外布局如何", "美的集团-000333"),
                new GoldenCase("hengrui_innovation", "创新药转型做得比较好的药企有哪些风险", "恒瑞医药-600276"),
                new GoldenCase("tencent_social", "靠社交流量和游戏的互联网平台护城河在哪", "腾讯控股-00700"),
                new GoldenCase("longi_solar", "光伏单晶硅片龙头面临什么价格战风险", "隆基绿能-601012"),
                new GoldenCase("yili_dairy", "乳制品常温奶市占率领先的公司渠道如何", "伊利股份-600887"),
                new GoldenCase("citics_broker", "券商行业综合实力第一的公司业绩与什么强相关", "中信证券-600030")
        );
    }

    /**
     * 种子语料：与生产 {@code KnowledgeInitializer} 同一批 9 篇真实公司基本面，带 {@code user_id="eval"} 租户。
     * 这里内联一份让测试自包含，避免依赖生产组件的私有方法或数据库初始化路径。
     */
    private static List<Document> seedCorpus() {
        return List.of(
                doc("【贵州茅台 600519】白酒龙头，主营高端酱香型白酒。核心产品飞天茅台具备极强品牌力与提价权，毛利率长期超90%，净利率约50%，ROE常年25%以上，几乎无有息负债、现金流充沛。护城河来自品牌稀缺性与产能地域限制。风险：高端白酒受宏观消费与政策影响，估值对增速预期敏感。", "贵州茅台-600519"),
                doc("【宁德时代 300750】全球动力电池龙头，主营动力电池与储能电池。市占率全球第一，绑定国内外主流车企，规模与研发投入构筑壁垒。毛利率约20%、受上游碳酸锂价格波动影响大。护城河来自规模效应、技术迭代与客户绑定。风险：行业产能过剩导致价格战，下游需求与补贴政策变化。", "宁德时代-300750"),
                doc("【招商银行 600036】零售银行标杆，主营零售与对公金融业务。以高净息差、低不良率、强中间业务收入著称，ROE在股份行中领先。护城河来自零售客户基础与风控文化。关键指标看净息差、不良率、拨备覆盖率、核心一级资本充足率。风险：宏观经济下行带来资产质量压力，息差收窄。", "招商银行-600036"),
                doc("【美的集团 000333】白色家电龙头，主营空调、冰洗等家电及工业技术（库卡机器人）。多品类布局、渠道与供应链效率高，海外营收占比超四成。毛利率约25%，分红稳定、现金流良好。护城河来自规模制造与渠道。风险：地产周期影响家电需求，原材料价格与汇率波动。", "美的集团-000333"),
                doc("【恒瑞医药 600276】创新药龙头，主营抗肿瘤、麻醉、造影剂等药品。研发投入占营收比例高，创新药管线丰富，正从仿制药向创新药转型。毛利率长期超80%。护城河来自研发能力与销售网络。风险：集采压低仿制药价格，创新药研发失败与审批不确定性。", "恒瑞医药-600276"),
                doc("【腾讯控股 00700】互联网龙头，主营社交（微信/QQ）、游戏、广告、金融科技与云。微信构筑极强流量护城河与生态闭环，游戏现金流稳定，投资版图广阔。毛利率约50%。护城河来自社交网络效应与平台生态。风险：游戏版号与监管政策，宏观广告需求波动。", "腾讯控股-00700"),
                doc("【隆基绿能 601012】光伏龙头，主营单晶硅片与组件。单晶技术路线领先，一体化布局降低成本。行业景气度与硅料价格强相关，毛利率波动大。护城河来自技术迭代与规模成本。风险：光伏产能过剩引发价格战，技术路线（如TOPCon/HJT）切换风险，海外贸易壁垒。", "隆基绿能-601012"),
                doc("【伊利股份 600887】乳制品龙头，主营液态奶、奶粉、冷饮。渠道下沉能力强、品牌覆盖广，常温奶市占率领先。毛利率约30%，经营稳健、分红率高。护城河来自渠道网络与品牌。风险：原奶价格周期波动，行业增速放缓，新生儿数量下降影响奶粉需求。", "伊利股份-600887"),
                doc("【中信证券 600030】券商龙头，主营经纪、投行、资管、自营。综合实力行业第一，投行与机构业务领先，业绩与资本市场景气度强相关（β属性明显）。护城河来自牌照、资本规模与客户资源。风险：市场成交量与行情下行直接压制业绩，监管政策与自营投资波动。", "中信证券-600030")
        );
    }

    private static Document doc(String content, String sourceName) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("user_id", EVAL_USER);
        meta.put("source_name", sourceName);
        return new Document(content, meta);
    }

    /**
     * Identity 改写器：原问题直接当唯一 query。
     * 用它隔离掉「chat-LLM 改写质量」这个抖动变量，让本测试只衡量 embedding 检索本身。
     *
     * <p>父类构造器只会 {@code build()} 出 ChatClient（存着、不调用），故传一个永不被调用的
     * 桩 ChatModel 即可；{@link #rewrite} 被覆盖后那个桩不会触达。</p>
     */
    private static final class IdentityQueryRewriter extends QueryRewriter {
        IdentityQueryRewriter() {
            super(ChatClient.builder(new UnusedChatModel()));
        }

        @Override
        public List<String> rewrite(String userQuestion) {
            List<String> out = new ArrayList<>();
            out.add(userQuestion == null ? "" : userQuestion.trim());
            return out;
        }
    }

    /** 永不被调用的 ChatModel 桩，仅用于满足 QueryRewriter 父类构造器。 */
    private static final class UnusedChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            throw new UnsupportedOperationException("identity rewriter must not call the chat model");
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("identity rewriter must not call the chat model");
        }
    }
}
