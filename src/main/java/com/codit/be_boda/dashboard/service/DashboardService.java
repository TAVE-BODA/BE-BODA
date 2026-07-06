package com.codit.be_boda.dashboard.service;

import com.codit.be_boda.analysis.domain.CoverageItem;
import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.dashboard.dto.CoverageCardResponse;
import com.codit.be_boda.analysis.dto.CoverageItemDto;
import com.codit.be_boda.dashboard.dto.DashboardResponse;
import com.codit.be_boda.analysis.repository.CoverageItemRepository;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final CoverageItemRepository coverageItemRepository;
    private final ObjectMapper objectMapper;

    public DashboardResponse getDashboard(Long analysisId) {
        PolicyAnalysis analysis = policyAnalysisRepository.findById(analysisId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "분석 결과를 찾을 수 없습니다. analysisId=" + analysisId
                ));

        List<CoverageItem> coverageItems =
                coverageItemRepository.findByPolicyAnalysisOrderByCoverageType(analysis);

        List<CoverageCardResponse> coverages = coverageItems.stream()
                .map(this::toCoverageCardResponse)
//                coverageItems 안에 있는 CoverageItem 하나하나를 CoverageCardResponse로 변환
                .toList();
//               변환된 결과를 다시 리스트로 모으기



        return new DashboardResponse(
                analysis.getId(),
                analysis.getAnalysisStatus(),
                analysis.getCompanyName(),
                analysis.getInsuranceStartDate(),
                analysis.getInsuranceEndDate(),
                coverages
        );
    }

//  CoverageItem Entity 하나를 CoverageCardResponse DTO 하나로 바꾸기
    private CoverageCardResponse toCoverageCardResponse(CoverageItem item) {
        Map<String, Object> detail = item.getDetail();

        List<CoverageItemDto> items = objectMapper.convertValue(
                detail.get("items"),
                new TypeReference<List<CoverageItemDto>>() {}
        );

        return new CoverageCardResponse(
                item.getCoverageType(),
                item.getIsDetected(),
                items,
                item.getExclusionKeywords()
        );
    }
}
