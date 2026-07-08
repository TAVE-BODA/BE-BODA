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
                .append("로 인정되고, 수술확인서의 수술명이 약관상 지급 조건에 해당하면 이 금액이 지급 후보가 될 수 있어요.\n\n");

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

    // 입력된 사고 유형과 자유입력에 맞는 수술비 항목 찾기
    private CoverageItemDto findMatchedSurgeryItem(Long analysisId, ChatMessageRequest request) {
        CoverageItemDto messageMatchedItem = findMessageMatchedSurgeryItem(analysisId, request);

        if (messageMatchedItem != null) {
            return messageMatchedItem;
        }

        return findIncidentMatchedSurgeryItem(analysisId, request);
    }

    // 자유입력 수술명 기준 후보 찾기
    private CoverageItemDto findMessageMatchedSurgeryItem(Long analysisId, ChatMessageRequest request) {
        List<String> messageKeywords = getMessageSurgeryKeywords(request.getMessage());

        if (messageKeywords.isEmpty()) {
            return null;
        }

        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!isSurgeryCoverage(coverageItem)) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                continue;
            }

            for (CoverageItemDto item : detail.items()) {
                for (String keyword : messageKeywords) {
                    if (containsKeyword(item, keyword)) {
                        return item;
                    }
                }
            }
        }

        return null;
    }

    // 사고 유형 기준 기본 수술비 후보 찾기
    private CoverageItemDto findIncidentMatchedSurgeryItem(Long analysisId, ChatMessageRequest request) {
        List<String> incidentKeywords = getIncidentSurgeryKeywords(request);

        List<CoverageItemInfo> coverageItems =
                coverageItemQueryRepository.findByAnalysisId(analysisId);

        for (CoverageItemInfo coverageItem : coverageItems) {
            if (!isSurgeryCoverage(coverageItem)) {
                continue;
            }

            CoverageLlmResponse detail = parseCoverageDetail(coverageItem.detail());

            if (detail.items() == null || detail.items().isEmpty()) {
                continue;
            }

            for (CoverageItemDto item : detail.items()) {
                for (String keyword : incidentKeywords) {
                    if (containsKeyword(item, keyword)) {
                        return item;
                    }
                }
            }
        }

        return null;
    }

    // 사고 유형 문구 변환
    private String getIncidentDescription(ChatMessageRequest request) {
        if (request.getIncidentType() != null
                && "INJURY".equals(request.getIncidentType().name())) {
            return "재해 또는 상해";
        }

        if (request.getIncidentType() != null
                && ("DISEASE".equals(request.getIncidentType().name())
                || "CHECKUP_FOUND".equals(request.getIncidentType().name()))) {
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

    private boolean isSurgeryCoverage(CoverageItemInfo coverageItem) {
        return "수술".equals(coverageItem.coverageType());
    }

    // 자유입력에서 수술 관련 검색 키워드 추출
    private List<String> getMessageSurgeryKeywords(String message) {
        if (message == null || message.isBlank()) {
            return List.of();
        }

        String normalizedMessage = normalize(message);

        return List.of(
                        "골절",
                        "인대",
                        "무릎",
                        "아킬레스",
                        "힘줄",
                        "연골",
                        "디스크",
                        "관절",
                        "척추",
                        "충수",
                        "맹장",
                        "제왕절개",
                        "절제",
                        "봉합",
                        "관혈",
                        "비관혈"
                ).stream()
                .filter(keyword -> normalizedMessage.contains(normalize(keyword)))
                .toList();
    }

    // 사고 유형별 기본 수술비 검색 키워드
    private List<String> getIncidentSurgeryKeywords(ChatMessageRequest request) {
        if (request.getIncidentType() == null) {
            return List.of("수술비", "수술급여금", "수술");
        }

        if ("INJURY".equals(request.getIncidentType().name())) {
            return List.of("재해수술비", "상해수술비", "재해수술", "상해수술");
        }

        if ("DISEASE".equals(request.getIncidentType().name())
                || "CHECKUP_FOUND".equals(request.getIncidentType().name())) {
            return List.of("질병수술비", "질병수술");
        }

        return List.of("수술비", "수술급여금", "수술");
    }

    private boolean containsKeyword(CoverageItemDto item, String keyword) {
        String normalizedKeyword = normalize(keyword);

        if (normalize(item.coverageName()).contains(normalizedKeyword)) {
            return true;
        }

        if (item.amounts() == null || item.amounts().isEmpty()) {
            return false;
        }

        return item.amounts().stream()
                .anyMatch(amount -> normalize(amount.condition()).contains(normalizedKeyword));
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }

        return value.replaceAll("\\s+", "");
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