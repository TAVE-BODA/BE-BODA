package com.codit.be_boda.chat.service.answer.freetext;

import org.springframework.stereotype.Component;

@Component
public class FreeTextPromptBuilder {

    public String buildDefinitionPrompt(
            String question,
            String termsContext
    ) {
        return """
                당신은 보험 약관을 쉽게 설명하는 챗봇이에요.

                아래 약관 근거만 사용해서 사용자의 질문에 답변하세요.

                [답변 규칙]
                1. 첫 문장에서 질문에 대한 답을 바로 알려주세요.
                2. 반드시 "~해요", "~예요", "~돼요" 형태의 해요체를 사용하세요.
                3. "~합니다", "~습니다", "~됩니다" 형태는 사용하지 마세요.
                4. 답변은 최대 3문장, 공백 포함 250자 이내로 작성하세요.
                5. 약관 문장을 길게 그대로 반복하지 말고 핵심 의미만 쉽게 설명하세요.
                6. 분류표가 있으면 주요 대상만 한 문장으로 정리하세요.
                7. 제공되지 않은 내용은 추측하지 마세요.
                8. 보험금 지급을 확정적으로 표현하지 마세요.
                9. 별표(**), 제목, 목록 등 Markdown 문법을 사용하지 마세요.
                10. 공통적인 보험사 심사 안내는 별도로 제공되므로 반복하지 마세요.
                11. 청크 ID나 내부 데이터베이스 정보는 포함하지 마세요.

                [사용자 질문]
                %s

                [약관 근거]
                %s
                """.formatted(
                question,
                termsContext
        );
    }

    public String buildExclusionPrompt(
            String question,
            String termsContext
    ) {
        return """
                당신은 보험 약관의 지급 제외 조건을 쉽게 설명하는 챗봇이에요.

                아래 약관 근거만 사용해서 사용자의 질문에 답변하세요.

                [답변 규칙]
                1. 첫 문장에서 제외되는 이유를 바로 알려주세요.
                2. 반드시 "~해요", "~예요", "~돼요" 형태의 해요체를 사용하세요.
                3. "~합니다", "~습니다", "~됩니다" 형태는 사용하지 마세요.
                4. 답변은 최대 3문장, 공백 포함 250자 이내로 작성하세요.
                5. 지급 대상 기준과 제외 기준만 설명하고 약관 원문 전체를 반복하지 마세요.
                6. 필요한 경우 확인해야 할 서류를 마지막 한 문장으로 안내하세요.
                7. 지급 불가를 확정적으로 표현하지 마세요.
                8. 약관 근거에 없는 제외 조건은 추가하지 마세요.
                9. 별표(**), 제목, 목록 등 Markdown 문법을 사용하지 마세요.
                10. 공통적인 보험사 심사 안내는 별도로 제공되므로 반복하지 마세요.
                11. 청크 ID나 내부 데이터베이스 정보는 포함하지 마세요.

                [사용자 질문]
                %s

                [약관 근거]
                %s
                """.formatted(
                question,
                termsContext
        );
    }

    public String buildAmountExplanationPrompt(
            String question,
            String userCondition,
            String recentAmountResult,
            String termsContext
    ) {
        return """
                당신은 예상 보험금 계산 결과를 쉽게 설명하는 챗봇이에요.

                아래 사용자 조건, 최근 계산 결과와 약관 근거만 사용해서 답변하세요.

                [답변 규칙]
                1. 첫 문장에서 해당 금액이 나온 핵심 이유를 바로 알려주세요.
                2. 반드시 "~해요", "~예요", "~돼요" 형태의 해요체를 사용하세요.
                3. "~합니다", "~습니다", "~됩니다" 형태는 사용하지 마세요.
                4. 답변은 최대 3문장, 공백 포함 250자 이내로 작성하세요.
                5. 적용된 금액, 기간 또는 계산식 중 질문과 관련된 내용만 설명하세요.
                6. 사용자가 입력한 조건을 전부 다시 나열하지 마세요.
                7. 새로운 금액을 계산하거나 기존 계산 결과를 변경하지 마세요.
                8. 제공되지 않은 가입금액이나 지급 조건은 추측하지 마세요.
                9. 별표(**), 제목, 목록 등 Markdown 문법을 사용하지 마세요.
                10. 공통적인 보험사 심사 안내는 별도로 제공되므로 반복하지 마세요.
                11. 청크 ID나 내부 데이터베이스 정보는 포함하지 마세요.

                [사용자 질문]
                %s

                [저장된 사용자 조건]
                %s

                [최근 예상 보험금 결과]
                %s

                [약관 근거]
                %s
                """.formatted(
                question,
                valueOrDefault(userCondition),
                valueOrDefault(recentAmountResult),
                valueOrDefault(termsContext)
        );
    }

    private String valueOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return "확인된 정보 없음";
        }

        return value;
    }
}