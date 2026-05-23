package com.travel.ai.eval.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.util.List;

/**
 * Optional Eval Contract V1 evidence and retrieval summary.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class EvalChatEvidenceSummary {

    private Integer retrievalHitCount;
    private Integer sourceCount;
    private Boolean lowConfidence;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> lowConfidenceReasons;

    private Boolean citationMembershipChecked;
    private String canonicalHitIdScheme;
    private Boolean contextTruncated;

    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> contextTruncationReasons;

    public Integer getRetrievalHitCount() {
        return retrievalHitCount;
    }

    public void setRetrievalHitCount(Integer retrievalHitCount) {
        this.retrievalHitCount = retrievalHitCount;
    }

    public Integer getSourceCount() {
        return sourceCount;
    }

    public void setSourceCount(Integer sourceCount) {
        this.sourceCount = sourceCount;
    }

    public Boolean getLowConfidence() {
        return lowConfidence;
    }

    public void setLowConfidence(Boolean lowConfidence) {
        this.lowConfidence = lowConfidence;
    }

    public List<String> getLowConfidenceReasons() {
        return lowConfidenceReasons;
    }

    public void setLowConfidenceReasons(List<String> lowConfidenceReasons) {
        this.lowConfidenceReasons = lowConfidenceReasons;
    }

    public Boolean getCitationMembershipChecked() {
        return citationMembershipChecked;
    }

    public void setCitationMembershipChecked(Boolean citationMembershipChecked) {
        this.citationMembershipChecked = citationMembershipChecked;
    }

    public String getCanonicalHitIdScheme() {
        return canonicalHitIdScheme;
    }

    public void setCanonicalHitIdScheme(String canonicalHitIdScheme) {
        this.canonicalHitIdScheme = canonicalHitIdScheme;
    }

    public Boolean getContextTruncated() {
        return contextTruncated;
    }

    public void setContextTruncated(Boolean contextTruncated) {
        this.contextTruncated = contextTruncated;
    }

    public List<String> getContextTruncationReasons() {
        return contextTruncationReasons;
    }

    public void setContextTruncationReasons(List<String> contextTruncationReasons) {
        this.contextTruncationReasons = contextTruncationReasons;
    }
}
