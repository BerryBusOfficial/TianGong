package com.taixu.tiangong.llm.entity;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

@Getter
@Builder
@Jacksonized
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class Option {
    private Integer seed;
    private Double temperature;
    private Integer topK;
    private Double topP;
    private Double minP;
    private List<String> stop;
    private Integer numCtx;
    private Integer numPredict;
}