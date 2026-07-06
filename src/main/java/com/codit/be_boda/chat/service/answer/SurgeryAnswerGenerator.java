package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.analysis.dto.CoverageAmountDto;
import com.codit.be_boda.analysis.dto.CoverageItemDto;
import com.codit.be_boda.analysis.dto.CoverageLlmResponse;
import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository;
import com.codit.be_boda.chat.repository.CoverageItemQueryRepository.CoverageItemInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Slf4j
@Component
@RequiredArgsConstructor
public class SurgeryAnswerGenerator {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;

    // CHIP_CLAIM 중 SURGERY 처리
    public String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        CoverageItemDto surgeryItem = findMatchedSurgeryItem(analysisId, request);

        if (surgeryItem == null) {
            return "가입하신 증권에서 입력하신 상황과 직접 매칭되는 수술비 보장 항목을 찾지 못했어요.";
        }

        StringBuilder answer = new StringBuilder();

        answer.append("청구 가능성이 있어요.\n\n")
                .append("가입하신 증권에서 ")
                .append(surgeryItem.coverageName())
                .append(" 보장이 확인돼요.\n")
                .append(getIncidentDescription(request))
                .append("로 인정되면 보험금을 받을 수 있어요.\n\n")
                .append("[확인된 보장]\n")
                .append("- ")
                .append(surgeryItem.coverageName())
                .append("\n");

        if (hasDifferentAmounts(surgeryItem)) {
            answer.append("\n[확인된 금액]\n")
                    .append("가입일 정보가 확인되지 않아 1년 이내/초과 여부를 정확히 판단하기 어려워요.\n")
                    .append("확인된 조건별 금액은 아래와 같아요.\n")
                    .append(buildConditionAmountLines(surgeryItem));
        } else {
            CoverageAmountDto amount = getFirstAmount(surgeryItem);

            if (amount != null && amount.coverageAmount() != null) {
                answer.append("\n[금액]\n")
                        .append("- ")
                        .append(String.format("%,d원", amount.coverageAmount()))
                        .append("\n");
            }
        }

        return answer.toString();
    }

    // CHIP_AMOUNT 중 SURGERY 처리
    public String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        CoverageItemDto surgeryItem = findMatchedSurgeryItem(analysisId, request);

        if (surgeryItem == null) {
            return "가입하신 증권에서 입력하신 상황과 직접 매칭되는 수술비 보장 항목을 찾지 못했어요.";
        }

        if (surgeryItem.amounts() == null || surgeryItem.amounts().isEmpty()) {
            return "수술비 보장은 확인됐지만, 예상 보험금 금액은 확인되지 않았어요.";
        }

        if (hasDifferentAmounts(surgeryItem)) {
            StringBuilder answer = new StringBuilder();

            answer.append("가입일 정보가 확인되지 않아 예상 보험금을 하나로 확정하기 어려워요.\n\n")
                    .append("가입하신 보험의 ")
                    .append(surgeryItem.coverageName())
                    .append("는 1년 이내/초과 여부에 따라 금액이 달라져요.\n\n")
                    .append("[확인된 금액]\n")
                    .append(buildConditionAmountLines(surgeryItem));

            return answer.toString();
        }

        CoverageAmountDto amount = getFirstAmount(surgeryItem);

        if (amount == null) {
            return "수술비 보장은 확인됐지만, 예상 보험금 금액은 확인되지 않았어요.";
        }

        if (amount.coverageAmount() == null) {
            return "수술비 보장은 확인됐지만, 정확한 금액은 약관 확인이 필요해요.";
        }

        StringBuilder answer = new StringBuilder();

        answer.append("수술을 받으시면 ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("이 나와요.\n\n");

        answer.append("가입하신 보험의 ")
                .append(surgeryItem.coverageName())
                .append(" 보장금액이 ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("이에요.\n")
                .append(getIncidentDescription(request))
                .append("로 인정되면 이 금액을 받을 수 있어요.\n\n");

        answer.append("[계산 내역]\n")
                .append("- ")
                .append(surgeryItem.coverageName())
                .append(": ")
                .append(amount.condition())
                .append(" ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("\n");

        return answer.toString();
    }

    // 입력된 사고 유형에 맞는 수술비 항목 찾기
    private CoverageItemDto findMatchedSurgeryItem(Long analysisId, ChatMessageRequest request) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"수술".equals(coverageItem.coverageType())) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                return null;
            }

            String targetKeyword = getSurgeryKeyword(request);

            return detail.items().stream()
                    .filter(item -> item.coverageName() != null)
                    .filter(item -> item.coverageName().contains(targetKeyword))
                    .findFirst()
                    .orElse(null);
        }

        return null;
    }

    // 사고 유형에 따라 질병 수술비 / 재해 수술비 키워드 선택
    private String getSurgeryKeyword(ChatMessageRequest request) {
        if (request.getIncidentType() != null
                && "INJURY".equals(request.getIncidentType().name())) {
            return "재해 수술비";
        }

        if (request.getIncidentType() != null
                && "DISEASE".equals(request.getIncidentType().name())) {
            return "질병 수술비";
        }

        return "수술비";
    }

    // 사고 유형 문구 변환
    private String getIncidentDescription(ChatMessageRequest request) {
        if (request.getIncidentType() != null
                && "INJURY".equals(request.getIncidentType().name())) {
            return "재해";
        }

        if (request.getIncidentType() != null
                && "DISEASE".equals(request.getIncidentType().name())) {
            return "질병";
        }

        return "입력하신 상황";
    }

    // 첫 번째 보장 금액 정보 조회
    private CoverageAmountDto getFirstAmount(CoverageItemDto item) {
        if (item.amounts() == null || item.amounts().isEmpty()) {
            return null;
        }

        return item.amounts().get(0);
    }

    // 조건별 금액이 서로 다른지 확인
    private boolean hasDifferentAmounts(CoverageItemDto item) {
        if (item.amounts() == null || item.amounts().isEmpty()) {
            return false;
        }

        Long firstAmount = item.amounts().get(0).coverageAmount();

        return item.amounts().stream()
                .anyMatch(amount -> !Objects.equals(firstAmount, amount.coverageAmount()));
    }

    // 조건별 금액 문구 생성
    private String buildConditionAmountLines(CoverageItemDto item) {
        StringBuilder builder = new StringBuilder();

        item.amounts().forEach(amount -> {
            builder.append("- ")
                    .append(amount.condition())
                    .append(": ");

            if (amount.coverageAmount() == null) {
                builder.append("약관 확인 필요");
            } else {
                builder.append(String.format("%,d원", amount.coverageAmount()));
            }

            builder.append("\n");
        });

        return builder.toString();
    }

    // detail JSON 파싱
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