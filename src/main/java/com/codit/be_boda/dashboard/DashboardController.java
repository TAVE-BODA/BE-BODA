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

//  대시보드 자동 생성 (증권 분석 완료시)

    // 사용자가 대시보드 확인 버튼을 누르면 저장된 대시보드 조회
    @GetMapping("/summary/{chatSessionId}")
    public DashboardResponse getDashboard(
            @PathVariable Long chatSessionId
    ) {
        return dashboardService.getDashboard(chatSessionId);
    }



    // 대시보드의 analysisId를 이용해 각 증권별 카드 조회
    @GetMapping("/analysis/{analysisId}")
    public DashcardResponse getDashcard(
            @PathVariable Long analysisId
    ) {
        return dashcardService.getDashboard(analysisId);
    }


}