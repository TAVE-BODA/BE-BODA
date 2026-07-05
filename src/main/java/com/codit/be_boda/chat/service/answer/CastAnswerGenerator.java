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

@Slf4j
@Component
@RequiredArgsConstructor
public class CastAnswerGenerator {

    private final CoverageItemQueryRepository coverageItemQueryRepository;
    private final ObjectMapper objectMapper;

    // CHIP_CLAIM 중 CAST 처리
    public String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"골절재해".equals(coverageItem.coverageType())) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                break;
            }

            return detail.items().stream()
                    .filter(item -> item.coverageName() != null)
                    .filter(item -> item.coverageName().contains("깁스"))
                    .findFirst()
                    .map(item -> buildCastClaimAnswer(
                            item,
                            coverageItem.exclusionKeywords(),
                            request
                    ))
                    .orElse("가입하신 증권에서 깁스 치료와 직접 매칭되는 보장 항목을 찾지 못했어요.");
        }

        return "가입하신 증권에서 골절·깁스 관련 보장 항목을 찾지 못했어요.";
    }

    // CHIP_AMOUNT 중 CAST 처리
    public String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!"골절재해".equals(coverageItem.coverageType())) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                break;
            }

            return detail.items().stream()
                    .filter(item -> item.coverageName() != null)
                    .filter(item -> item.coverageName().contains("깁스"))
                    .findFirst()
                    .map(item -> buildCastAmountAnswer(
                            item,
                            coverageItem.exclusionKeywords(),
                            request
                    ))
                    .orElse("가입하신 증권에서 깁스 치료와 직접 매칭되는 보장 항목을 찾지 못했어요.");
        }

        return "가입하신 증권에서 골절·깁스 관련 보장 항목을 찾지 못했어요.";
    }

    // CAST 청구 가능 여부 답변 문장 생성
    private String buildCastClaimAnswer(
            CoverageItemDto item,
            String exclusionKeywords,
            ChatMessageRequest request
    ) {
        String amountText = "";

        if (item.amounts() != null && !item.amounts().isEmpty()) {
            CoverageAmountDto amount = item.amounts().get(0);

            if (amount.coverageAmount() != null) {
                amountText = String.format(
                        "- %s: %s %,d원\n",
                        item.coverageName(),
                        amount.condition(),
                        amount.coverageAmount()
                );
            }
        }

        StringBuilder answer = new StringBuilder();

        if (isSplintCast(request)) {
            answer.append("청구가 어려울 수 있어요.\n\n")
                    .append("가입하신 증권에서 깁스(Cast) 치료 보장은 확인되지만, 해당 보장은 부목을 제외하는 조건이 있어요.\n")
                    .append("입력하신 치료가 반깁스·부목에 해당한다면 청구 대상에서 제외될 수 있습니다.\n\n");
        } else {
            answer.append("청구 가능성이 있어요.\n\n")
                    .append("가입하신 증권에서 깁스(Cast) 치료 보장이 확인돼요.\n")
                    .append("입력하신 치료가 반깁스·부목이 아니라 정식 깁스라면 청구 가능성이 있습니다.\n\n");
        }

        answer.append("[확인된 보장]\n");

        if (amountText.isBlank()) {
            answer.append("- ").append(item.coverageName()).append("\n");
        } else {
            answer.append(amountText);
        }

        appendExclusionKeywords(answer, exclusionKeywords);

        return answer.toString();
    }

    // CAST 예상 보험금 답변 문장 생성
    private String buildCastAmountAnswer(
            CoverageItemDto item,
            String exclusionKeywords,
            ChatMessageRequest request
    ) {
        if (isSplintCast(request)) {
            return "예상 보험금을 계산하기 어려워요.\n\n"
                    + "가입하신 증권에서 깁스(Cast) 치료 보장은 확인되지만, 해당 보장은 부목을 제외하는 조건이 있어요.\n"
                    + "입력하신 치료가 반깁스·부목에 해당한다면 지급 대상에서 제외될 수 있습니다.";
        }

        if (item.amounts() == null || item.amounts().isEmpty()) {
            return "깁스 치료 보장은 확인됐지만, 예상 보험금 금액은 확인되지 않았어요.";
        }

        CoverageAmountDto amount = item.amounts().get(0);

        if (amount.coverageAmount() == null) {
            return "깁스 치료 보장은 확인됐지만, 예상 보험금 금액은 약관 확인이 필요해요.";
        }

        StringBuilder answer = new StringBuilder();

        answer.append("예상 보험금은 약 ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("이에요.\n\n");

        answer.append("[계산 내역]\n")
                .append("- ")
                .append(item.coverageName())
                .append(": ")
                .append(amount.condition())
                .append(" ")
                .append(String.format("%,d원", amount.coverageAmount()))
                .append("\n");

        appendExclusionKeywords(answer, exclusionKeywords);

        return answer.toString();
    }

    // 반깁스/부목 여부 확인
    private boolean isSplintCast(ChatMessageRequest request) {
        return request.getCastInfo() != null
                && request.getCastInfo().getCastType() != null
                && "SPLINT".equals(request.getCastInfo().getCastType().name());
    }

    // 주의사항 문구 추가
    private void appendExclusionKeywords(StringBuilder answer, String exclusionKeywords) {
        if (exclusionKeywords != null && !exclusionKeywords.isBlank()) {
            answer.append("\n[주의사항]\n")
                    .append("- ")
                    .append(exclusionKeywords.replace(", ", "\n- "))
                    .append("\n");
        }
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