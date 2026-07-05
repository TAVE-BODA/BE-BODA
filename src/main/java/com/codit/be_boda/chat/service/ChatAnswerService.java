package com.codit.be_boda.chat.service;

import com.codit.be_boda.analysis.dto.CoverageLlmResponse;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository.CoverageItemInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAnswerService {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;

    public String generateCoverageBasedAnswer(Long analysisId) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        if (coverageItems.isEmpty()) {
            return "증권 분석 결과에서 확인된 보장 항목이 없습니다.";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("증권 분석 결과에서 확인된 보장 항목을 읽었어요.\n\n");

        for (CoverageItemInfo coverageItem : coverageItems) {
            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            answer.append("- ")
                    .append(coverageItem.coverageType())
                    .append(": ")
                    .append(Boolean.TRUE.equals(coverageItem.isDetected()) ? "확인됨" : "미확인")
                    .append("\n");

            answer.append("  detail 길이: ")
                    .append(coverageItem.detail() == null ? "null" : coverageItem.detail().length())
                    .append("\n");

            answer.append("  item 개수: ")
                    .append(detail.items() == null ? "null" : detail.items().size())
                    .append("\n");

            if (detail.items() != null && !detail.items().isEmpty()) {
                detail.items().forEach(item ->
                        answer.append("  · ")
                                .append(item.coverageName())
                                .append("\n")
                );
            }
        }

        return answer.toString();
    }

    private CoverageLlmResponse parseCoverageDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return new CoverageLlmResponse(false, List.of(), null);
        }

        try {
            return objectMapper.readValue(detail, CoverageLlmResponse.class);
        } catch (Exception e) {
            log.warn("coverage_item detail 파싱 실패. detail={}", detail, e);
            return new CoverageLlmResponse(false, List.of(), null);
        }
    }
}