package com.codit.be_boda.mypage.service;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.analysis.repository.TermsDocumentRepository;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.dashboard.repository.DashboardRepository;
import com.codit.be_boda.mypage.dto.MypageInsuranceResponse;
import com.codit.be_boda.mypage.dto.MypageResponse;
import com.codit.be_boda.user.domain.User;
import com.codit.be_boda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;


@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MypageService {

    private final UserRepository userRepository;
    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final TermsDocumentRepository termsDocumentRepository;
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final DashboardRepository dashboardRepository;
    private final ChatSessionRepository chatSessionRepository;

    public MypageResponse getMyPage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "사용자를 찾을 수 없습니다. userId=" + userId
                ));

//      사용자의 보험증권 전체 조회
        List<PolicyAnalysis> analyses =
                policyAnalysisRepository
                        .findByUserOrderByCreatedAtDesc(user);

//      증권 목록을 마이페이지 DTO 목록으로 변환
        List<MypageInsuranceResponse> insurances =
                analyses.stream()
                        .map(this::toInsuranceResponse)
                        .toList();

//      최종 마이페이지 응답 생성
        return MypageResponse.of(user, insurances);
    }

//  보험증권 하나를 DTO로 변환하는 메서드
    private MypageInsuranceResponse toInsuranceResponse(
            PolicyAnalysis analysis
    ) {
        Long analysisId = analysis.getId(); //analysisId 추출

        String companyName = extractCompanyName(analysis); //보험사명 추출

//      현재 증권의 analysisId로 chat_session_policy를 조회
        Long existingChatSessionId =
                chatSessionPolicyRepository
                        .findByAnalysisId(analysisId)
                        .map(ChatSessionPolicy::getChatSessionId)
                        .orElse(null); //채팅방이 없다면 null을 반환(없지 않겠지만)

//      대시보드 존재 여부 확인(증권 분석이 완료되었다면 대시보드는 자동으로 생성되었을 것)
        boolean dashboardAvailable =
                existingChatSessionId != null
                        && dashboardRepository.existsById(
                        existingChatSessionId
                );

//      약관 테이브(terms_document)에 해당 증권의 유무로 약관의 유무를 판단
        boolean termsUploaded =
                termsDocumentRepository.existsByAnalysisId(analysisId);

        // TODO: 조건입력 데이터 저장 위치 확정 후 실제 조회
        boolean conditionCompleted = false;

        // 현재 기준: 조건입력이 완료된 경우 새 채팅 가능
        boolean canCreateNewChat = conditionCompleted;

        return new MypageInsuranceResponse(
                analysisId,
                companyName,
                dashboardAvailable,
                termsUploaded,
                conditionCompleted,
                existingChatSessionId,
                canCreateNewChat
        );
    }

    private String extractCompanyName(
            PolicyAnalysis analysis
    ) {
        Map<String, Object> extractedData =
                analysis.getExtractedData();

        if (extractedData == null) {
            return "보험사 정보 없음";
        }

        Object companyName =
                extractedData.get("companyName");

        if (companyName == null) {
            return "보험사 정보 없음";
        }

        return companyName.toString();
    }
}