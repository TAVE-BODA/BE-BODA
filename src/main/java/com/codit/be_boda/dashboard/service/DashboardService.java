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

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final DashboardRepository dashboardRepository;
    private final UserRepository userRepository;
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;

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

    // chat_session_policy 여러 행에서 analysisId 목록 추출
    private List<Long> extractAnalysisIds(
            List<ChatSessionPolicy> sessionPolicies
    ) {
        return sessionPolicies.stream()
                .map(ChatSessionPolicy::getAnalysisId)
                .distinct()
                .toList();
    }

    // 대시보드 생성 및 저장
    @Transactional
    public DashboardResponse createDashboard(DashboardResponse request) {

        Long chatSessionId = request.chatSessionId();

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

        // 같은 chatSessionId를 가진 중간 테이블의 여러 행 조회
        List<ChatSessionPolicy> sessionPolicies =
                findSessionPolicies(chatSessionId);

        // 여러 행에서 analysisId 목록 추출
        List<Long> analysisIds =
                extractAnalysisIds(sessionPolicies);

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