package com.codit.be_boda.dashboard.service;

import com.codit.be_boda.analysis.dto.CoverageLlmResponse;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.dashboard.domain.Dashboard;
import com.codit.be_boda.dashboard.dto.CoverageSummaryDto;
import com.codit.be_boda.dashboard.dto.DashboardResponse;
import com.codit.be_boda.dashboard.repository.DashboardRepository;
import com.codit.be_boda.user.domain.User;
import com.codit.be_boda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.dto.CoverageAmountDto;
import com.codit.be_boda.analysis.dto.CoverageItemDto;
import com.fasterxml.jackson.databind.ObjectMapper;


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
import java.time.LocalDate;
import java.time.LocalDateTime;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final DashboardRepository dashboardRepository;
    private final UserRepository userRepository;
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final CoverageItemRepository coverageItemRepository;
    private final ObjectMapper objectMapper;

    // 저장된 대시보드 조회
    public DashboardResponse getDashboard(Long chatSessionId) {
        Dashboard dashboard = dashboardRepository.findById(chatSessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "대시보드를 찾을 수 없습니다. chatSessionId=" + chatSessionId
                ));

        return DashboardResponse.from(dashboard);
    }

    // chatSessionId에 연결된 chat_session_policy 여러 행 조회
    private List<ChatSessionPolicy> findSessionPolicies(Long chatSessionId) {
        List<ChatSessionPolicy> sessionPolicies =
                chatSessionPolicyRepository.findByChatSessionId(chatSessionId);

        if (sessionPolicies.isEmpty()) {
            throw new IllegalArgumentException(
                    "해당 채팅 세션에 연결된 보험증권이 없습니다. "
                            + "chatSessionId=" + chatSessionId
            );
        }

        return sessionPolicies;
    }

//   1. 중간 테이블인 chat_session_policy에서 분석 ID 목록만 뽑는 역할
//   동일한 chat_session_id에 대해 analysisId 목록을 analysis_ids에 list 타입으로 추출
    private List<Long> extractAnalysisIds(
            List<ChatSessionPolicy> sessionPolicies
    ) {
        return sessionPolicies.stream()
                .map(ChatSessionPolicy::getAnalysisId)
                .distinct()
                .toList();
    }


//  2. 그 분석 ID들로 실제 policy_analysis 데이터를 조회
//  analysis_id(analysisIds)를 사용해서 policy_analysis테이블 조회
    private List<PolicyAnalysis> findPolicyAnalyses(
            List<Long> analysisIds
    ) {
//      조회된 결과는 analyses 리스트에 저장
        List<PolicyAnalysis> analyses =
                policyAnalysisRepository.findAllById(analysisIds);

        if (analyses.isEmpty()) {
            throw new IllegalArgumentException(
                    "조회된 증권 분석 결과가 없습니다. analysisIds="
                            + analysisIds
            );
        }

        if (analyses.size() != analysisIds.size()) {
            throw new IllegalArgumentException(
                    "일부 증권 분석 결과를 찾을 수 없습니다. "
                            + "요청한 analysisIds=" + analysisIds
                            + ", 조회된 개수=" + analyses.size()
            );
        }

        return analyses;
    }


    // 같은 채팅 세션에 연결된 모든 PolicyAnalysis가 COMPLETE인지 확인
    private boolean areAllAnalysesCompleted(
            List<PolicyAnalysis> analyses
    ) {
        return analyses.stream()
                .allMatch(analysis ->
                        "COMPLETE".equals(
                                String.valueOf(
                                        analysis.getAnalysisStatus()
                                )
                        )
                );
    }



//  3. analysis_id(analyses리스트에 있음)로 CoverageItem 조회
    private List<CoverageItem> findCoverageItems(
            List<PolicyAnalysis> analyses
    ) {
        List<CoverageItem> coverageItems =
                coverageItemRepository.findAllByPolicyAnalysisIn(analyses);

        if (coverageItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "조회된 보장 항목이 없습니다."
            );
        }

        return coverageItems;
    }


//  전체 CoverageItem 중에서 isDetected = true인 항목만 남긴 뒤 coverageType별로 묶기
    private Map<String, List<CoverageItem>> groupByCoverageType(
            List<CoverageItem> coverageItems
    ) {
        return coverageItems.stream()
                .filter(item ->
                        Boolean.TRUE.equals(item.getIsDetected())
                )
                .collect(Collectors.groupingBy(
                        CoverageItem::getCoverageType
                ));
    }

//  CoverageItem 테이블에서 detail에 저장된 값 사용
//  CoverageItem.detail의 {"items": [...]} 구조를 DTO 목록으로 변환
    private List<CoverageItemDto> convertDetail(
            CoverageItem coverageItem
    ) {
        if (coverageItem.getDetail() == null) {
            return List.of();
        }

        CoverageLlmResponse response = objectMapper.convertValue(
                coverageItem.getDetail(),
                CoverageLlmResponse.class
        );

        if (response.items() == null) {
            return List.of();
        }

        return response.items();
    }


//    각 카드별 금액 모두 추출(최대,최소를 계산하기 위해)
    private List<Long> extractCoverageAmounts(
            List<CoverageItem> coverageItems
    ) {
        return coverageItems.stream()
                .flatMap(coverageItem ->
                        convertDetail(coverageItem).stream()
                )
                .filter(Objects::nonNull)
                .flatMap(item -> {
                    if (item.amounts() == null) {
                        return java.util.stream.Stream.empty();
                    }

                    return item.amounts().stream();
                })
                .filter(Objects::nonNull)
                .map(CoverageAmountDto::coverageAmount)
                .filter(Objects::nonNull)

                .filter(amount -> amount > 0)
                .toList();
    }
//  각 타입별 최대,최소 금액 반환
    private Long findMinAmount(List<Long> amounts) {
        return amounts.stream()
                .min(Long::compareTo)
                .orElse(null);
    }
    private Long findMaxAmount(List<Long> amounts) {
        return amounts.stream()
                .max(Long::compareTo)
                .orElse(null);
    }


    // 조회한 모든 PolicyAnalysis의 extractedData에서 보험사명 목록 추출
    private List<String> extractAllCompanyNames(
            List<PolicyAnalysis> analyses
    ) {
        return analyses.stream()
                .map(PolicyAnalysis::getExtractedData)
                .filter(Objects::nonNull)
                .map(extractedData -> extractedData.get("companyName"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(companyName -> !companyName.isBlank())
                .distinct()
                .toList();
    }

    // 해당 coverageType별로 보장하는 증권들의 보험사명 추출
    private List<String> extractCompanyNames(
            List<CoverageItem> coverageItems
    ) {
        return coverageItems.stream()
                .map(CoverageItem::getPolicyAnalysis)
                .filter(Objects::nonNull)
                .map(PolicyAnalysis::getExtractedData)
                .filter(Objects::nonNull)
                .map(extractedData -> extractedData.get("companyName"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(companyName -> !companyName.isBlank())
                .distinct()
                .toList();
    }

    private String resolveUnit(String coverageType) {
        return switch (coverageType) {
            case "입원" -> "원/일";
            case "치아" -> "원/개";
            default -> "원";
        };
    }

    // CoverageItem 생성일 중 가장 최근 날짜를 분석 완료일로 사용
    private LocalDate extractAnalysisCompletedAt(
            List<CoverageItem> coverageItems
    ) {
        return coverageItems.stream()
                .map(CoverageItem::getCreatedAt)
                .filter(Objects::nonNull)
                .map(LocalDateTime::toLocalDate)
                .max(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalArgumentException(
                        "분석 완료일을 찾을 수 없습니다."
                ));
    }

    private CoverageSummaryDto createCoverageSummary(
            String coverageType,
            List<CoverageItem> coverageItems
    ) {
        List<Long> amounts =
                extractCoverageAmounts(coverageItems);

        Long minAmount =
                findMinAmount(amounts);

        Long maxAmount =
                findMaxAmount(amounts);

        List<String> companyNames =
                extractCompanyNames(coverageItems);

        return new CoverageSummaryDto(
                coverageType,
                minAmount,
                maxAmount,
                resolveUnit(coverageType),
                companyNames
        );
    }

    private List<CoverageSummaryDto> createCoverageSummaries(
            Map<String, List<CoverageItem>> groupedCoverageItems
    ) {
        return groupedCoverageItems.entrySet().stream()
                .map(entry ->
                        createCoverageSummary(
                                entry.getKey(),
                                entry.getValue()
                        )
                )
                .toList();
    }



//    조회한 PolicyAnalysis에서 피보험자명 추출
//    하나의 채팅 세션에 서로 다른 피보험자의 증권이 섞이는 것도 막음
//    PolicyAnalysis의 extractedData에서 피보험자명 추출
    private String extractInsuredName(
        List<PolicyAnalysis> analyses
    ) {
        List<String> insuredNames = analyses.stream()
                .map(PolicyAnalysis::getExtractedData)
                .filter(Objects::nonNull)
                .map(extractedData -> extractedData.get("insuredName"))
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(insuredName -> !insuredName.isBlank())
                .distinct()
                .toList();

        if (insuredNames.isEmpty()) {
            throw new IllegalArgumentException(
                 "피보험자명을 찾을 수 없습니다."
            );
        }

        if (insuredNames.size() > 1) {
            throw new IllegalArgumentException(
                 "서로 다른 피보험자의 증권이 포함되어 있습니다. "
                            + "insuredNames=" + insuredNames
            );
        }

        return insuredNames.get(0);
}


    // 분석 완료 서비스에서 호출할 자동 생성 진입점
    // 모든 분석이 완료되지 않았다면 생성하지 않고 종료
    // 이미 생성된 경우에도 다시 생성하지 않음
    @Transactional
    public void createDashboardIfReady(
            Long chatSessionId,
            Long userId
    ) {
        if (dashboardRepository.existsById(chatSessionId)) {
            return;
        }

        List<ChatSessionPolicy> sessionPolicies =
                findSessionPolicies(chatSessionId);

        List<Long> analysisIds =
                extractAnalysisIds(sessionPolicies);

        List<PolicyAnalysis> analyses =
                findPolicyAnalyses(analysisIds);

        if (!areAllAnalysesCompleted(analyses)) {
            return;
        }

        createDashboard(
                chatSessionId,
                userId,
                analysisIds,
                analyses
        );
    }

    // 기존 createDashboard(DashboardResponse request)를 제거하고,
    // 자동 생성에 필요한 chatSessionId와 userId 기반으로 변경
    //
    // analysisIds와 analyses는 createDashboardIfReady에서 이미 조회했으므로
    // 중복 조회하지 않고 전달받아 사용
    @Transactional
    public DashboardResponse createDashboard(
            Long chatSessionId,
            Long userId,
            List<Long> analysisIds,
            List<PolicyAnalysis> analyses
    ) {
        if (dashboardRepository.existsById(chatSessionId)) {
            throw new IllegalArgumentException(
                    "이미 존재하는 대시보드입니다. chatSessionId="
                            + chatSessionId
            );
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "사용자를 찾을 수 없습니다. userId="
                                + userId
                ));

        String insuredName =
                extractInsuredName(analyses);

        List<String> companyNames =
                extractAllCompanyNames(analyses);

        List<CoverageItem> coverageItems =
                findCoverageItems(analyses);

        LocalDate analysisCompletedAt =
                extractAnalysisCompletedAt(coverageItems);

        Map<String, List<CoverageItem>> groupedCoverageItems =
                groupByCoverageType(coverageItems);

        if (groupedCoverageItems.isEmpty()) {
            throw new IllegalArgumentException(
                    "탐지된 보장 항목이 없습니다. chatSessionId="
                            + chatSessionId
            );
        }

        List<CoverageSummaryDto> coverageSummaries =
                createCoverageSummaries(groupedCoverageItems);

        Dashboard dashboard = Dashboard.builder()
                .chatSessionId(chatSessionId)
                .user(user)
                .insuredName(insuredName)
                .analysisCompletedAt(analysisCompletedAt)
                .analysisIds(analysisIds)
                .companyNames(companyNames)
                .coverageSummaries(coverageSummaries)
                .build();

        Dashboard savedDashboard =
                dashboardRepository.save(dashboard);

        return DashboardResponse.from(savedDashboard);
    }
}