package com.codit.be_boda.dashboard.service;

import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.dashboard.domain.Dashboard;
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

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final DashboardRepository dashboardRepository;
    private final UserRepository userRepository;
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final CoverageItemRepository coverageItemRepository;

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





    // 대시보드 생성 및 저장
    @Transactional
    public DashboardResponse createDashboard(DashboardResponse request) {

        Long chatSessionId = request.chatSessionId();

        // 같은 chatSessionId를 가진 중간 테이블의 여러 행 조회
        List<ChatSessionPolicy> sessionPolicies =
                findSessionPolicies(chatSessionId);


        // 여러 행에서 analysisId 목록 추출
        List<Long> analysisIds =
                extractAnalysisIds(sessionPolicies);


        List<PolicyAnalysis> analyses =
                findPolicyAnalyses(analysisIds);

        List<CoverageItem> coverageItems =
                findCoverageItems(analyses);

        // 동일한 chatSessionId의 대시보드 중복 생성 방지
        if (dashboardRepository.existsById(chatSessionId)) {
            throw new IllegalArgumentException(
                    "이미 존재하는 대시보드입니다. chatSessionId="
                            + chatSessionId
            );
        }

        // 사용자 조회
        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "사용자를 찾을 수 없습니다. userId="
                                + request.userId()
                ));



        Dashboard dashboard = Dashboard.builder()
                .chatSessionId(chatSessionId)
                .user(user)
                .insuredName(request.insuredName())
                .analysisCompletedAt(request.analysisCompletedAt())

                // request.analysisIds()를 사용하지 않고
                // chat_session_policy 테이블에서 직접 조회한 값 저장
                .analysisIds(analysisIds)

                .companyNames(request.companyNames())
                .coverageSummaries(request.coverageSummaries())
                .build();

        Dashboard savedDashboard =
                dashboardRepository.save(dashboard);

        return DashboardResponse.from(savedDashboard);
    }
}