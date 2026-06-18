package com.codit.be_boda.chat;

import com.codit.be_boda.rag.RagService;
import com.codit.be_boda.user.UserSession;
import com.codit.be_boda.user.UserSession.ChatMessage;
import com.codit.be_boda.user.UserSession.InsuranceCondition;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

//챗봇 답변 생성 — Confidence 기반 모델 라우팅
//mini 모델 1차 답변 → 자가 평가(0~1) → 기준 미달 시 full 모델 재생성
// 재생성 범위 좀 더 고도화.. (정확한 평가 기준 필요.)
@Service
public class ChatService {

    private final OpenAiChatModel chatModel;
    private final RagService ragService;
    private final boolean mockMode;

    @Value("${app.llm.mini-model:gpt-4o-mini}")
    private String miniModel;

    @Value("${app.llm.full-model:gpt-4o}")
    private String fullModel;

    @Value("${app.llm.confidence-threshold:0.75}")
    private double threshold;

    public ChatService(OpenAiChatModel chatModel, RagService ragService,
                       @Value("${app.mock-mode:false}") boolean mockMode) {
        this.chatModel = chatModel;
        this.ragService = ragService;
        this.mockMode = mockMode;
    }

    public record AnswerResult(String answer, String evidence, boolean usedFallback) {}

    //자유 양식 (추후 양식 추가.. )
    public AnswerResult chat(UserSession session, String question) {
        if (mockMode) return mock(question);
        return route(session, question);
    }

    // case 추가. 각 case에서 칩 선택
    public AnswerResult chipAnswer(UserSession session, String chipType) {
        String q = switch (chipType) {
            case "CLAIM"     -> "제 상황에서 보험금 청구가 가능한가요?";
            case "AMOUNT"    -> "보험금을 얼마나 받을 수 있나요?";
            case "DOCUMENTS" -> "보험금 청구에 필요한 서류가 무엇인가요?";
            case "OVERVIEW"  -> "내 보험 전체 보장 내역을 한눈에 보여주세요.";
            default          -> chipType;
        };
        return chat(session, q);
    }

    public void addHistory(UserSession session, String role, String content, String evidence) {
        ChatMessage msg = new ChatMessage();
        msg.setRole(role); msg.setContent(content);
        msg.setEvidence(evidence); msg.setTimestamp(LocalDateTime.now());
        session.getChatHistory().add(msg);
    }


    //Confidence 라우팅
    //TODO:
    private AnswerResult route(UserSession session, String question) {
        List<String> chunks = ragService.search(session.getUserId(), question);
        String context = String.join("\n\n---\n\n", chunks);
        String system  = buildPrompt(session, context);
        String evidence = chunks.isEmpty() ? null : "📋 관련 약관:\n" + chunks.get(0);

        String miniAnswer = call(miniModel, system, question);
        double score = confidence(miniAnswer, question, context);

        if (score >= threshold) return new AnswerResult(miniAnswer, evidence, false);

        String fullAnswer = call(fullModel, system, question);
        return new AnswerResult(fullAnswer, evidence, true);
    }

    //TODO (1) : 관련 프롬포트 좀 더 고도화. 결과값 확인하며 평가지점 명확히 산출하기.
    private double confidence(String answer, String question, String context) {
        String prompt = String.format("""
            다음 보험 AI 답변의 품질을 0.0~1.0 숫자 하나로만 평가해.
            - 약관 근거 명확 → 높은 점수
            - "모르겠다", "확인 필요" 표현 → 낮은 점수
            - 구체적 금액/가능 여부 없음 → 낮은 점수
            - 컨텍스트 없는 내용 창작 → 낮은 점수
            
            질문: %s
            컨텍스트: %s
            답변: %s
            
            숫자만 반환(예: 0.82):
            """, question,
            context.length() > 300 ? context.substring(0, 300) : context,
            answer);
        try {
            return Double.parseDouble(call(miniModel, "You are a strict quality evaluator.", prompt).trim().replaceAll("[^0-9.]", ""));
        } catch (Exception e) { return 0.0; }
    }

    private String call(String model, String system, String user) {
        return ChatClient.builder(chatModel).build().prompt()
            .options(OpenAiChatOptions.builder().model(model).temperature(0.2).build())
            .system(system).user(user).call().content();
    }


    //TODO (2) : 관련 프롬포트 좀 더 고도화. 결과값 확인하며 평가지점 명확히 산출하기.
    private String buildPrompt(UserSession session, String context) {
        var sb = new StringBuilder("""
            당신은 '보다'입니다. 보험생이 AI 보험 분석 어시스턴트예요.
            - 친근하고 쉬운 말투로 답변 (결론 → 근거 → 다음 단계)
            - 반드시 제공된 약관/증권에 근거, 모르면 솔직하게
            - 복잡한 케이스는 보험사 고객센터 안내
            """);

        InsuranceCondition c = session.getCondition();
        if (c != null) {
            sb.append("\n[보험 조건]\n")
              .append("- 치료/사고: ").append(c.getTreatmentType()).append("\n")
              .append("- 병원 이용: ").append(c.getHospitalUsage()).append("\n");
            if (c.getTreatmentDate() != null) sb.append("- 시기: ").append(c.getTreatmentDate()).append("\n");
            if (c.getEstimatedCost() != null) sb.append("- 진료비: ").append(c.getEstimatedCost()).append("\n");
        }

        if (session.getPolicyText() != null) {
            String s = session.getPolicyText();
            sb.append("\n[보험증권]\n").append(s, 0, Math.min(3000, s.length())).append("\n");
        }

        if (!context.isBlank())
            sb.append("\n[관련 약관 조항]\n").append(context).append("\n");

        sb.append("\n위 내용에만 근거하여 답변하세요.");
        return sb.toString();
    }

    // 목데이터 (LLM 연결 X시 해당 데이터 나옴 )
    private AnswerResult mock(String q) {
        String lower = q.toLowerCase();
        if (lower.contains("청구") || lower.contains("가능"))
            return new AnswerResult("✅ **청구 가능해요!**\n\n예상 수령액: 약 45만원\n\n필요 서류:\n1. 진단서\n2. 영수증\n3. 청구서", "[mock] 제5조 실손의료비", false);
        if (lower.contains("얼마") || lower.contains("금액"))
            return new AnswerResult("💰 **예상 수령액: 약 45만원**\n\n실손: 36만원 + 입원일당: 15만원", null, false);
        if (lower.contains("서류"))
            return new AnswerResult("📄 **필요 서류**\n\n1. 보험금 청구서\n2. 진단서\n3. 영수증 + 세부내역서", null, false);
        return new AnswerResult("조금 더 구체적으로 말씀해주시면 정확하게 답변드릴게요 😊", null, false);
    }
}
