package com.codit.be_boda.dashboard;

import com.codit.be_boda.dashboard.dto.DashboardResponse;
import com.codit.be_boda.dashboard.dto.DashcardResponse;
import com.codit.be_boda.dashboard.service.DashboardService;
import com.codit.be_boda.dashboard.service.DashcardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;
    private final DashcardService dashcardService;

//  대시보드 생성
    @PostMapping
    public DashboardResponse createDashboard(
            @RequestBody DashboardResponse request
    ) {
        return dashboardService.createDashboard(request);
    }

    // 먼저 대시보드 조회
    @GetMapping("/summary/{sessionId}")
    public DashboardResponse getDashboard(
            @PathVariable String sessionId
    ) {
        return dashboardService.getDashboard(sessionId);
    }

    // 사용자가 카드 클릭 후 증권분석 상세 조회
    @GetMapping("/analysis/{analysisId}")
    public DashcardResponse getDashcard(
            @PathVariable Long analysisId
    ) {
        return dashcardService.getDashboard(analysisId);
    }


}