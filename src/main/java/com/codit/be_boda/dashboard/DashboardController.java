package com.codit.be_boda.dashboard;

import com.codit.be_boda.dashboard.dto.DashboardResponse;
import com.codit.be_boda.dashboard.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardService dashboardService;

    @GetMapping("/{analysisId}")
    public DashboardResponse getDashboard(@PathVariable Long analysisId) {
        return dashboardService.getDashboard(analysisId);
    }
}