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
    @GetMapping("/summary/{chatSessionId}")
    public DashboardResponse getDashboard(
            @PathVariable Long chatSessionId
    ) {
        return dashboardService.getDashboard(chatSessionId);
    }

    // 대시보드에 들어있는 증권분석id로 각 증권별 카드 조회
    @GetMapping("/analysis/{analysisId}")
    public DashcardResponse getDashcard(
            @PathVariable Long analysisId
    ) {
        return dashcardService.getDashboard(analysisId);
    }


}