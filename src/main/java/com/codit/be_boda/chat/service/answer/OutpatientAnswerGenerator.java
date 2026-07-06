package com.codit.be_boda.chat.service.answer;

import com.codit.be_boda.chat.dto.request.ChatMessageRequest;
import org.springframework.stereotype.Component;

@Component
public class OutpatientAnswerGenerator {

    public String generateClaimAnswer(Long analysisId, ChatMessageRequest request) {
        StringBuilder answer = new StringBuilder();

        answer.append("통원·외래 치료는 관련 보장 확인이 필요해요.\n\n")
                .append("통원 치료는 실손의료비, 통원비, 처방조제비, 특정 치료비 등 여러 보장과 연결될 수 있어요.\n")
                .append("현재 입력값만으로는 어떤 보장 항목에 해당하는지 단정하기 어려워요.\n\n")
                .append("[확인이 필요한 정보]\n")
                .append("- 진료비 영수증\n")
                .append("- 진료비 세부내역서\n")
                .append("- 진단명 또는 치료명\n")
                .append("- 약제비가 있다면 처방전 또는 약제비 영수증\n\n")
                .append("정확한 청구 가능 여부는 실제 치료명, 진료비 항목, 약관의 통원 보장 조건 확인이 필요해요.");

        return answer.toString();
    }

    public String generateAmountAnswer(Long analysisId, ChatMessageRequest request) {
        StringBuilder answer = new StringBuilder();

        answer.append("통원·외래 치료의 예상 보험금은 현재 단계에서 정확히 계산하기 어려워요.\n\n")
                .append("통원 치료는 진료비, 공제금액, 자기부담금, 통원 한도, 약제비 여부에 따라 보험금이 달라질 수 있어요.\n")
                .append("특히 실손의료비 보장은 실제 결제 금액과 세부내역서를 기준으로 계산해야 해요.\n\n")
                .append("[확인이 필요한 정보]\n")
                .append("- 진료비 총액\n")
                .append("- 급여/비급여 항목\n")
                .append("- 공제금액 또는 자기부담금\n")
                .append("- 통원 1회 한도\n")
                .append("- 약제비 발생 여부\n\n")
                .append("현재는 예상 보험금을 하나로 계산하지 않고, 약관 및 진료비 세부내역 확인이 필요한 항목으로 안내드릴게요.");

        return answer.toString();
    }
}