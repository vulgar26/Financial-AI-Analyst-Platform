package com.travel.ai.agent;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "app.knowledge.init.enabled", havingValue = "true")
public class KnowledgeInitializer {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeInitializer.class);

    private final VectorStore vectorStore;

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeInitializer(VectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {

        if (jdbcTemplate.queryForObject("SELECT COUNT(*) FROM vector_store", Integer.class) > 0) {
            log.info("知识库已存在，跳过初始化");
            return;
        }

        List<Document> documents = List.of(
                docForEval("【贵州茅台 600519】白酒龙头，主营高端酱香型白酒。核心产品飞天茅台具备极强品牌力与提价权，毛利率长期超90%，净利率约50%，ROE常年25%以上，几乎无有息负债、现金流充沛。护城河来自品牌稀缺性与产能地域限制。风险：高端白酒受宏观消费与政策影响，估值对增速预期敏感。", "贵州茅台-600519"),
                docForEval("【宁德时代 300750】全球动力电池龙头，主营动力电池与储能电池。市占率全球第一，绑定国内外主流车企，规模与研发投入构筑壁垒。毛利率约20%、受上游碳酸锂价格波动影响大。护城河来自规模效应、技术迭代与客户绑定。风险：行业产能过剩导致价格战，下游需求与补贴政策变化。", "宁德时代-300750"),
                docForEval("【招商银行 600036】零售银行标杆，主营零售与对公金融业务。以高净息差、低不良率、强中间业务收入著称，ROE在股份行中领先。护城河来自零售客户基础与风控文化。关键指标看净息差、不良率、拨备覆盖率、核心一级资本充足率。风险：宏观经济下行带来资产质量压力，息差收窄。", "招商银行-600036"),
                docForEval("【美的集团 000333】白色家电龙头，主营空调、冰洗等家电及工业技术（库卡机器人）。多品类布局、渠道与供应链效率高，海外营收占比超四成。毛利率约25%，分红稳定、现金流良好。护城河来自规模制造与渠道。风险：地产周期影响家电需求，原材料价格与汇率波动。", "美的集团-000333"),
                docForEval("【恒瑞医药 600276】创新药龙头，主营抗肿瘤、麻醉、造影剂等药品。研发投入占营收比例高，创新药管线丰富，正从仿制药向创新药转型。毛利率长期超80%。护城河来自研发能力与销售网络。风险：集采压低仿制药价格，创新药研发失败与审批不确定性。", "恒瑞医药-600276"),
                docForEval("【腾讯控股 00700】互联网龙头，主营社交（微信/QQ）、游戏、广告、金融科技与云。微信构筑极强流量护城河与生态闭环，游戏现金流稳定，投资版图广阔。毛利率约50%。护城河来自社交网络效应与平台生态。风险：游戏版号与监管政策，宏观广告需求波动。", "腾讯控股-00700"),
                docForEval("【隆基绿能 601012】光伏龙头，主营单晶硅片与组件。单晶技术路线领先，一体化布局降低成本。行业景气度与硅料价格强相关，毛利率波动大。护城河来自技术迭代与规模成本。风险：光伏产能过剩引发价格战，技术路线（如TOPCon/HJT）切换风险，海外贸易壁垒。", "隆基绿能-601012"),
                docForEval("【伊利股份 600887】乳制品龙头，主营液态奶、奶粉、冷饮。渠道下沉能力强、品牌覆盖广，常温奶市占率领先。毛利率约30%，经营稳健、分红率高。护城河来自渠道网络与品牌。风险：原奶价格周期波动，行业增速放缓，新生儿数量下降影响奶粉需求。", "伊利股份-600887"),
                docForEval("【中信证券 600030】券商龙头，主营经纪、投行、资管、自营。综合实力行业第一，投行与机构业务领先，业绩与资本市场景气度强相关（β属性明显）。护城河来自牌照、资本规模与客户资源。风险：市场成交量与行情下行直接压制业绩，监管政策与自营投资波动。", "中信证券-600030")
        );
        vectorStore.add(documents);
        log.info("知识库初始化完成，共加载 {} 条数据", documents.size());
    }

    private static Document docForEval(String content, String sourceName) {
        Map<String, Object> meta = new HashMap<>();
        // 评测专用租户：与 eval chat 路径的 user_id 过滤对齐，避免匿名请求跨用户读取真实数据。
        meta.put("user_id", "eval");
        meta.put("source_name", sourceName);
        return new Document(content, meta);
    }
}
