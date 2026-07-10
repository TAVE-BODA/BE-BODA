package com.codit.be_boda.dashboard.service;

import com.codit.be_boda.dashboard.domain.Dashboard;
import com.codit.be_boda.dashboard.dto.DashboardResponse;
import com.codit.be_boda.dashboard.repository.DashboardRepository;
import com.codit.be_boda.user.domain.User;
import com.codit.be_boda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final DashboardRepository dashboardRepository;
    private final UserRepository userRepository;

//  저장된 대시보드 조회
    public DashboardResponse getDashboard(String sessionId) {
        Dashboard dashboard = dashboardRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "대시보드를 찾을 수 없습니다. sessionId=" + sessionId
                ));

        return DashboardResponse.from(dashboard);
    }

//  대시보드 생성 및 저장
    @Transactional
    public DashboardResponse createDashboard(DashboardResponse request) {

        if (dashboardRepository.existsById(request.sessionId())) {
            throw new IllegalArgumentException(
                    "이미 존재하는 대시보드입니다. sessionId=" + request.sessionId()
            );
        }

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "사용자를 찾을 수 없습니다. userId=" + request.userId()
                ));

        Dashboard dashboard = Dashboard.builder()
                .sessionId(request.sessionId())
                .user(user)
                .insuredName(request.insuredName())
                .analysisCompletedAt(request.analysisCompletedAt())
                .analysisIds(request.analysisIds())
                .companyNames(request.companyNames())
                .coverageSummaries(request.coverageSummaries())
                .build();

        Dashboard savedDashboard = dashboardRepository.save(dashboard);

        return DashboardResponse.from(savedDashboard);
    }
}