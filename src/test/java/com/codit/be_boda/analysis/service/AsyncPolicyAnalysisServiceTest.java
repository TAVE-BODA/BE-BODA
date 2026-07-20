package com.codit.be_boda.analysis.service;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.dashboard.service.DashboardService;
import com.codit.be_boda.upload.service.S3Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AsyncPolicyAnalysisServiceTest {

    private final AsyncPolicyAnalysisService service =
            new AsyncPolicyAnalysisService(
                    mock(PolicyAnalysisRepository.class),
                    mock(CoverageItemRepository.class),
                    mock(ChatSessionPolicyRepository.class),
                    mock(S3Service.class),
                    mock(OpenAiChatModel.class),
                    new ObjectMapper(),
                    mock(DashboardService.class)
            );

    @Test
    @DisplayName("삼성 팩 건강보험(2604)의 입원 담보 금액을 확정 금액으로 보정한다")
    void normalizeSamsungPack2604HospitalizationAmounts() {
        PolicyAnalysis analysis = PolicyAnalysis.builder()
                .maskedText("삼성 팩 건강보험(2604) 보험증권")
                .build();

        Map<String, Object> extractedData = Map.of(
                "productName",
                "삼성 팩 건강보험(2604)(무배당,무해약환급금형)"
        );
        Map<String, Object> detail = hospitalizationDetail();

        ReflectionTestUtils.invokeMethod(
                service,
                "normalizeSamsungPack2604HospitalizationAmounts",
                analysis,
                extractedData,
                "입원",
                detail
        );

        assertThat(amountsByCoverageName(detail))
                .containsEntry("2·3인실 입원(종합병원이상)", List.of(10_000L, 10_000L))
                .containsEntry("2·3인실 입원(상급종합병원)", List.of(40_000L, 40_000L))
                .containsEntry("상급병실 1인실(종합병원이상)", List.of(30_000L))
                .containsEntry("상급병실 1인실(상급종합병원)", List.of(70_000L));
    }

    @Test
    @DisplayName("분리 추출된 2인실과 3인실 담보도 네 개의 표준 입원 담보로 통합한다")
    void normalizeSeparatelyExtractedTwoAndThreeRoomItems() {
        PolicyAnalysis analysis = PolicyAnalysis.builder()
                .maskedText("삼성 팩 건강보험(2604) 보험증권")
                .build();
        Map<String, Object> detail = separatelyExtractedHospitalizationDetail();

        ReflectionTestUtils.invokeMethod(
                service,
                "normalizeSamsungPack2604HospitalizationAmounts",
                analysis,
                Map.of("productName", "삼성 팩 건강보험(2604)"),
                "입원",
                detail
        );

        Map<String, List<Long>> amounts = amountsByCoverageName(detail);

        assertThat(amounts)
                .hasSize(4)
                .containsEntry("2·3인실 입원(종합병원이상)", List.of(10_000L, 10_000L))
                .containsEntry("2·3인실 입원(상급종합병원)", List.of(40_000L, 40_000L))
                .containsEntry("상급병실 1인실(종합병원이상)", List.of(30_000L))
                .containsEntry("상급병실 1인실(상급종합병원)", List.of(70_000L));
    }

    @Test
    @DisplayName("다른 보험 상품의 입원 담보 금액은 변경하지 않는다")
    void doesNotNormalizeOtherProduct() {
        PolicyAnalysis analysis = PolicyAnalysis.builder()
                .maskedText("다른 보험 상품의 보험증권")
                .build();
        Map<String, Object> detail = hospitalizationDetail();

        ReflectionTestUtils.invokeMethod(
                service,
                "normalizeSamsungPack2604HospitalizationAmounts",
                analysis,
                Map.of("productName", "다른 건강보험"),
                "입원",
                detail
        );

        assertThat(amountsByCoverageName(detail))
                .containsEntry("2·3인실 입원(종합병원이상)", List.of(70_000L, 70_000L))
                .containsEntry("2·3인실 입원(상급종합병원)", List.of(70_000L, 70_000L))
                .containsEntry("상급병실 1인실(종합병원이상)", List.of(40_000L))
                .containsEntry("상급병실 1인실(상급종합병원)", List.of(40_000L));
    }

    private Map<String, Object> hospitalizationDetail() {
        List<Object> items = new ArrayList<>();
        items.add(item("2·3인실 입원(종합병원이상)", 70_000L, 70_000L));
        items.add(item("2·3인실 입원(상급종합병원)", 70_000L, 70_000L));
        items.add(item("상급병실 1인실(종합병원이상)", 40_000L));
        items.add(item("상급병실 1인실(상급종합병원)", 40_000L));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("isDetected", true);
        detail.put("items", items);
        return detail;
    }

    private Map<String, Object> separatelyExtractedHospitalizationDetail() {
        List<Object> items = new ArrayList<>();
        items.add(item("2인실 입원(종합병원이상)", 10_000L));
        items.add(item("3인실 입원(종합병원이상)", 10_000L));
        items.add(item("2인실 입원(상급종합병원)", 70_000L));
        items.add(item("3인실 입원(상급종합병원)", 10_000L));
        items.add(item("상급병실 1인실(종합병원이상)", 40_000L));
        items.add(item("상급병실 1인실(상급종합병원)", 40_000L));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("isDetected", true);
        detail.put("items", items);
        return detail;
    }

    private Map<String, Object> item(
            String coverageName,
            Long... amounts
    ) {
        List<Object> amountItems = new ArrayList<>();

        for (int index = 0; index < amounts.length; index++) {
            Map<String, Object> amount = new LinkedHashMap<>();
            amount.put(
                    "condition",
                    amounts.length == 1
                            ? "1일당"
                            : index == 0
                              ? "계약일부터 1년 초과 1일당"
                              : "계약일부터 1년 이내 1일당"
            );
            amount.put("coverageAmount", amounts[index]);
            amountItems.add(amount);
        }

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("coverageName", coverageName);
        item.put("amounts", amountItems);
        return item;
    }

    private Map<String, List<Long>> amountsByCoverageName(
            Map<String, Object> detail
    ) {
        Map<String, List<Long>> result = new LinkedHashMap<>();
        List<?> items = (List<?>) detail.get("items");

        for (Object itemObject : items) {
            Map<?, ?> item = (Map<?, ?>) itemObject;
            String coverageName = String.valueOf(item.get("coverageName"));
            List<?> amounts = (List<?>) item.get("amounts");
            List<Long> coverageAmounts = amounts.stream()
                    .map(amountObject -> (Map<?, ?>) amountObject)
                    .map(amount -> ((Number) amount.get("coverageAmount")).longValue())
                    .toList();

            result.put(coverageName, coverageAmounts);
        }

        return result;
    }
}