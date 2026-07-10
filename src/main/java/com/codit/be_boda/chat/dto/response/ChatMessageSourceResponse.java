package com.codit.be_boda.chat.dto.response;

import com.codit.be_boda.chat.type.SourceStatus;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.util.List;

@Getter
@Builder
public class ChatMessageSourceResponse {

    private Long messageId;
    private SourceStatus sourceStatus;
    private String notice;
    private List<SourceItem> sources;

    @Getter
    @Builder
    public static class SourceItem {
        private Long sourceId;
        private Long chunkId;
        private String title;
        private String citedText;
        private String clauseType;
        private BigDecimal relevanceScore;
    }

    // 약관 미업로드 응답 생성
    public static ChatMessageSourceResponse termsNotUploaded(Long messageId) {
        return ChatMessageSourceResponse.builder()
                .messageId(messageId)
                .sourceStatus(SourceStatus.TERMS_NOT_UPLOADED)
                .notice("약관 근거를 확인하려면 약관 업로드가 필요해요.")
                .sources(List.of())
                .build();
    }

    // 약관 근거 없음 응답 생성
    public static ChatMessageSourceResponse sourceNotFound(Long messageId) {
        return ChatMessageSourceResponse.builder()
                .messageId(messageId)
                .sourceStatus(SourceStatus.SOURCE_NOT_FOUND)
                .notice("연결된 약관에서 해당 답변의 근거 조항을 찾지 못했어요.")
                .sources(List.of())
                .build();
    }

    // 약관 근거 있음 응답 생성
    public static ChatMessageSourceResponse available(Long messageId, List<SourceItem> sources) {
        return ChatMessageSourceResponse.builder()
                .messageId(messageId)
                .sourceStatus(SourceStatus.AVAILABLE)
                .notice("약관 근거를 확인했어요.")
                .sources(sources)
                .build();
    }
}