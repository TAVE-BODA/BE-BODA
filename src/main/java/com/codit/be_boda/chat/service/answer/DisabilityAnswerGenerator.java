package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import org.springframework.stereotype.Component;

@Component
public class DisabilityAnswerGenerator {

    public String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        StringBuilder answer = new StringBuilder();

        answer.append("장해·후유장해 보장은 추가 확인이 필요해요.\n\n")
                .append("후유장해 보장은 단순 진단명만으로 판단하기 어렵고, 장해 부위와 장해율에 따라 청구 가능 여부가 달라질 수 있어요.\n\n")
                .append("[확인이 필요한 정보]\n")
                .append("- 후유장해진단서\n")
                .append("- 장해 부위\n")
                .append("- 장해율 또는 지급률\n")
                .append("- 사고 또는 질병 발생 경위\n")
                .append("- 약관상 장해분류표 해당 여부\n\n")
                .append("정확한 청구 가능 여부는 후유장해진단서와 약관의 장해분류표 확인이 필요해요.");

        return answer.toString();
    }

    public String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        StringBuilder answer = new StringBuilder();

        answer.append("장해·후유장해 보험금은 현재 입력값만으로 정확히 계산하기 어려워요.\n\n")
                .append("후유장해 보험금은 보통 가입금액에 장해 지급률을 곱해 계산되며, 장해율과 약관의 장해분류표에 따라 금액이 달라질 수 있어요.\n\n")
                .append("[계산에 필요한 정보]\n")
                .append("- 후유장해 보장 가입금액\n")
                .append("- 장해율 또는 지급률\n")
                .append("- 장해 부위\n")
                .append("- 동일 사고로 인한 장해인지 여부\n")
                .append("- 약관상 지급 제한 조건\n\n")
                .append("따라서 현재 단계에서는 예상 보험금을 하나로 계산하지 않고, 장해율 및 약관 확인이 필요한 항목으로 안내드릴게요.");

        return answer.toString();
    }
}