package com.codit.be_boda.mypage.service;

import com.codit.be_boda.analysis.domain.PolicyAnalysis;
import com.codit.be_boda.analysis.domain.TermsDocument;
import com.codit.be_boda.analysis.repository.PolicyAnalysisRepository;
import com.codit.be_boda.analysis.repository.TermsDocumentRepository;
import com.codit.be_boda.chat.entity.ChatSession;
import com.codit.be_boda.chat.entity.ChatSessionPolicy;
import com.codit.be_boda.chat.repository.ChatSessionPolicyRepository;
import com.codit.be_boda.chat.repository.ChatSessionRepository;
import com.codit.be_boda.dashboard.repository.DashboardRepository;
import com.codit.be_boda.global.support.InsurerNameResolver;
import com.codit.be_boda.mypage.dto.MypageChatResponse;
import com.codit.be_boda.mypage.dto.MypageInsuranceResponse;
import com.codit.be_boda.mypage.dto.MypageResponse;
import com.codit.be_boda.user.domain.User;
import com.codit.be_boda.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

// 마이페이지 조회
// 구조: 보험사 카테고리 → 채팅방 목록
// 보험사 카테고리 판정
//1순위: 채팅방에 연결된 약관의 보험사 (약관이 보험사를 확정)
//2순위: 채팅방에 첫 번째로 연결된 증권의 보험사
// -> 한 채팅방에 여러 보험사의 증권이 섞여도 카테고리는 하나로만 매핑된다
// 채팅방 정렬: 세션 생성 시각 오름차순
// 뱃지/버튼 판정: "마지막 채팅"(= 목록의 마지막 항목) 기준
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MypageService {

    private static final DateTimeFormatter CHAT_TITLE_FORMAT =
            DateTimeFormatter.ofPattern("yyyy.MM.dd");
    private static final String DONE_STATUS = "DONE";

    private final UserRepository userRepository;
    private final PolicyAnalysisRepository policyAnalysisRepository;
    private final TermsDocumentRepository termsDocumentRepository;
    private final ChatSessionPolicyRepository chatSessionPolicyRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final DashboardRepository dashboardRepository;
    private final InsurerNameResolver insurerNameResolver;

    public MypageResponse getMyPage(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "사용자를 찾을 수 없습니다. userId=" + userId
                ));

        // 사용자의 증권 전체 (id → 엔티티)
        List<PolicyAnalysis> analyses =
                policyAnalysisRepository.findByUserOrderByCreatedAtDesc(user);

        Map<Long, PolicyAnalysis> analysisById = new HashMap<>();
        for (PolicyAnalysis analysis : analyses) {
            analysisById.put(analysis.getId(), analysis);
        }

        // 약관 조회 캐시 (같은 약관을 여러 채팅방이 공유할 수 있음)
        Map<Long, TermsDocument> termsCache = new HashMap<>();

        // 채팅방 (생성 시각 오름차순)
        List<ChatSession> sessions =
                chatSessionRepository.findByUserIdOrderByCreatedAtAsc(userId);

        LinkedHashMap<String, Category> categories = new LinkedHashMap<>();
        Set<Long> linkedAnalysisIds = new HashSet<>();

        for (ChatSession session : sessions) {
            List<Long> analysisIds = chatSessionPolicyRepository
                    .findByChatSessionIdOrderByIdAsc(session.getChatSessionId())
                    .stream()
                    .map(ChatSessionPolicy::getAnalysisId)
                    .toList();

            linkedAnalysisIds.addAll(analysisIds);

            TermsDocument terms = findActiveTerms(session.getTermsDocumentId(), termsCache);
            String companyName = resolveCompanyName(terms, analysisIds, analysisById);
            String companyKey = insurerNameResolver.canonical(companyName);

            Category category = categories.computeIfAbsent(
                    companyKey, key -> new Category(key, displayName(companyKey, companyName))
            );
            category.addSession(session, analysisIds, terms);
        }

        // 채팅방에 연결되지 않은 증권도 카테고리로 노출 (업로드만 하고 채팅 전인 경우)
        for (PolicyAnalysis analysis : analyses) {
            if (linkedAnalysisIds.contains(analysis.getId())) {
                continue;
            }
            String companyName = analysis.getCompanyName();
            String companyKey = insurerNameResolver.canonical(companyName);

            categories.computeIfAbsent(
                    companyKey, key -> new Category(key, displayName(companyKey, companyName))
            ).addAnalysis(analysis.getId());
        }

        List<MypageInsuranceResponse> insurers = categories.values().stream()
                .map(category -> toInsuranceResponse(category, analysisById))
                .toList();

        return MypageResponse.of(user, insurers);
    }

    // 카테고리 보험사 판정
    // 순위: 약관의 보험사 / 2순위: 첫 번째로 연결된 증권의 보험사
    // 첫 증권의 보험사가 아직 추출되지 않았으면(비동기 분석 전) 다음 증권으로 넘어간다
    private String resolveCompanyName(TermsDocument terms,
                                      List<Long> analysisIds,
                                      Map<Long, PolicyAnalysis> analysisById) {
        if (terms != null
                && terms.getCompanyName() != null
                && !terms.getCompanyName().isBlank()) {
            return terms.getCompanyName();
        }

        for (Long analysisId : analysisIds) {
            PolicyAnalysis analysis = analysisById.get(analysisId);
            if (analysis == null) {
                continue;
            }
            String companyName = analysis.getCompanyName();
            if (companyName != null && !companyName.isBlank()) {
                return companyName;
            }
        }
        return null;
    }

    // 삭제되지 않은 약관만 유효 처리 (soft delete 반영)
    private TermsDocument findActiveTerms(Long termsDocumentId,
                                          Map<Long, TermsDocument> cache) {
        if (termsDocumentId == null) {
            return null;
        }
        if (cache.containsKey(termsDocumentId)) {
            return cache.get(termsDocumentId);
        }

        TermsDocument terms = termsDocumentRepository.findById(termsDocumentId)
                .filter(document -> !document.isDeleted())
                .orElse(null);

        cache.put(termsDocumentId, terms);
        return terms;
    }

    private MypageInsuranceResponse toInsuranceResponse(Category category,
                                                        Map<Long, PolicyAnalysis> analysisById) {
        List<Long> analysisIds = new ArrayList<>(category.analysisIds);

        // 대표 증권 = 카테고리에 처음 등록된 증권
        PolicyAnalysis representative = analysisIds.isEmpty()
                ? null
                : analysisById.get(analysisIds.get(0));

        List<MypageChatResponse> chats = category.chats;
        MypageChatResponse lastChat = chats.isEmpty() ? null : chats.get(chats.size() - 1);

        // 뱃지/버튼은 "마지막 채팅" 기준
        boolean termsUploaded = lastChat != null && lastChat.termsUploaded();
        boolean conditionCompleted = lastChat != null && lastChat.conditionCompleted();
        boolean canUploadTermsToContinue = lastChat != null && !termsUploaded;

        boolean dashboardAvailable = chats.stream()
                .anyMatch(MypageChatResponse::dashboardAvailable);

        // 어떤 채팅방에도 연결되지 않은 증권 (프론트가 "새 채팅에 끌어올 대상"으로 사용)
        Set<Long> linkedIds = new HashSet<>();
        for (MypageChatResponse chat : chats) {
            linkedIds.addAll(chat.analysisIds());
        }
        List<Long> unlinkedAnalysisIds = analysisIds.stream()
                .filter(analysisId -> !linkedIds.contains(analysisId))
                .toList();

        return new MypageInsuranceResponse(
                category.companyKey,
                category.companyName,
                buildTitle(representative, category.companyName),
                toLocalDate(earliestCreatedAt(analysisIds, analysisById)),
                analysisIds,
                unlinkedAnalysisIds,
                representative == null ? null : representative.getAnalysisStatus(),
                isAllDone(analysisIds, analysisById),
                termsUploaded,
                conditionCompleted,
                dashboardAvailable,
                canUploadTermsToContinue,
                chats.size(),
                chats
        );
    }

    // 카드 제목: 대표 증권의 상품명, 없으면 "OO 보험증권"
    private String buildTitle(PolicyAnalysis representative, String companyName) {
        if (representative != null) {
            Map<String, Object> extractedData = representative.getExtractedData();
            if (extractedData != null) {
                Object productName = extractedData.get("productName");
                if (productName != null && !productName.toString().isBlank()) {
                    return productName.toString();
                }
            }
        }
        return companyName + " 보험증권";
    }

    private boolean isAllDone(List<Long> analysisIds, Map<Long, PolicyAnalysis> analysisById) {
        if (analysisIds.isEmpty()) {
            return false;
        }
        return analysisIds.stream()
                .map(analysisById::get)
                .filter(java.util.Objects::nonNull)
                .allMatch(analysis -> DONE_STATUS.equals(analysis.getAnalysisStatus()));
    }

    private LocalDateTime earliestCreatedAt(List<Long> analysisIds,
                                            Map<Long, PolicyAnalysis> analysisById) {
        return analysisIds.stream()
                .map(analysisById::get)
                .filter(java.util.Objects::nonNull)
                .map(PolicyAnalysis::getCreatedAt)
                .filter(java.util.Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
    }

    private LocalDate toLocalDate(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toLocalDate();
    }

    private String displayName(String companyKey, String rawCompanyName) {
        if (rawCompanyName != null && !rawCompanyName.isBlank()) {
            return companyKey;
        }
        return InsurerNameResolver.UNKNOWN;
    }

    // 보험사 카테고리 누적용 내부 구조
    private final class Category {
        private final String companyKey;
        private final String companyName;
        private final LinkedHashSet<Long> analysisIds = new LinkedHashSet<>();
        private final List<MypageChatResponse> chats = new ArrayList<>();

        private Category(String companyKey, String companyName) {
            this.companyKey = companyKey;
            this.companyName = companyName;
        }

        private void addAnalysis(Long analysisId) {
            analysisIds.add(analysisId);
        }

        private void addSession(ChatSession session, List<Long> sessionAnalysisIds, TermsDocument terms) {
            analysisIds.addAll(sessionAnalysisIds);

            Long chatSessionId = session.getChatSessionId();
            LocalDate createdDate = toLocalDate(session.getCreatedAt());

            chats.add(new MypageChatResponse(
                    chatSessionId,
                    (createdDate == null ? "" : createdDate.format(CHAT_TITLE_FORMAT)) + " 대화",
                    createdDate,
                    sessionAnalysisIds,
                    terms == null ? null : terms.getId(),
                    terms != null,
                    session.getSystemPrompt() != null,
                    dashboardRepository.existsById(chatSessionId)
            ));
        }
    }

    // Optional 사용 여부와 무관하게 null 안전하게 처리하기 위한 헬퍼
    @SuppressWarnings("unused")
    private <T> Optional<T> optional(T value) {
        return Optional.ofNullable(value);
    }
}
