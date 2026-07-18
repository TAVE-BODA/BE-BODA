package com.codit.be_boda.chat.service.answer.freetext;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

// 키워드 기반 질문 분류
@Component
public class FreeTextIntentClassifier {

    public Intent classify(String question) {
        if (question == null || question.isBlank()) {
            return Intent.AMBIGUOUS;
        }

        String normalizedQuestion = normalize(question);

        if (isOutOfScope(normalizedQuestion)) {
            return Intent.OUT_OF_SCOPE;
        }

        if (isAmbiguous(normalizedQuestion)) {
            return Intent.AMBIGUOUS;
        }

        if (isExclusionQuestion(normalizedQuestion)) {
            return Intent.EXCLUSION_EXPLANATION;
        }

        if (isAmountQuestion(normalizedQuestion)) {
            return Intent.AMOUNT_EXPLANATION;
        }

        if (isDefinitionQuestion(normalizedQuestion)) {
            return Intent.TERM_DEFINITION;
        }

        return Intent.UNKNOWN;
    }

    public List<String> extractSearchKeywords(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }

        String normalizedQuestion = normalize(question);
        List<String> keywords = new ArrayList<>();

        if (normalizedQuestion.contains("5대재해골절")) {
            keywords.add("5대재해골절");
            keywords.add("5대 재해골절");
        }

        if (normalizedQuestion.contains("정식깁스")
                || normalizedQuestion.contains("반깁스")
                || normalizedQuestion.contains("부목")) {

            keywords.add("깁스(Cast)치료");
            keywords.add("깁스");
            keywords.add("부목");
        }

        if (normalizedQuestion.contains("보험연도")) {
            keywords.add("보험연도");
        }

        if (normalizedQuestion.contains("영구치발치")
                || normalizedQuestion.contains("발치")) {

            keywords.add("영구치 발치");
            keywords.add("영구치");
            keywords.add("발치");
        }

        if (normalizedQuestion.contains("치아파절")) {
            keywords.add("치아의 파절 제외");
            keywords.add("치아 파절");
            keywords.add("재해골절");
        }

        if (normalizedQuestion.contains("통원")) {
            keywords.add("통원");
            keywords.add("실손의료비");
            keywords.add("보상하지 않는");
        }

        if (!keywords.isEmpty()) {
            return keywords.stream()
                    .distinct()
                    .toList();
        }

        return extractGeneralKeywords(question);
    }

    private boolean isDefinitionQuestion(String question) {
        return containsAny(
                question,
                "뭐야",
                "무슨뜻",
                "뜻이야",
                "의미",
                "정의",
                "어떤거",
                "어떤것"
        );
    }

    private boolean isAmountQuestion(String question) {
        boolean asksWhy = containsAny(
                question,
                "왜",
                "계산",
                "산정",
                "금액",
                "얼마"
        );

        boolean mentionsAmountCondition = containsAny(
                question,
                "원",
                "일",
                "년이내",
                "년초과",
                "가입후",
                "보험금",
                "입원기간"
        );

        return asksWhy && mentionsAmountCondition;
    }

    private boolean isExclusionQuestion(String question) {
        return containsAny(
                question,
                "왜안돼",
                "왜제외",
                "제외돼",
                "제외야",
                "계산이안돼",
                "받을수없",
                "반깁스",
                "부목",
                "치아파절"
        );
    }

    private boolean isAmbiguous(String question) {
        return question.equals("왜")
                || question.equals("이게뭐야")
                || question.equals("그게뭐야")
                || question.equals("그건받을수있어")
                || question.equals("받을수있어")
                || question.equals("둘다되는거야")
                || question.equals("둘다돼")
                || question.equals("이건뭐야");
    }

    private boolean isOutOfScope(String question) {
        return containsAny(
                question,
                "약추천",
                "감기약",
                "진통제추천",
                "병원추천",
                "어느병원",
                "수술받는게좋",
                "치료받는게좋",
                "고소",
                "소송",
                "법률상담"
        );
    }

    private List<String> extractGeneralKeywords(String question) {
        return List.of(question
                        .replace("뭐야", "")
                        .replace("무슨 뜻이야", "")
                        .replace("뜻이 뭐야", "")
                        .replace("왜", "")
                        .replace("알려줘", "")
                        .replace("설명해줘", "")
                        .replace("?", "")
                        .trim())
                .stream()
                .filter(keyword -> !keyword.isBlank())
                .toList();
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }

        return false;
    }

    private String normalize(String value) {
        return value
                .replaceAll("\\s+", "")
                .replace("?", "")
                .replace("!", "")
                .toLowerCase();
    }

    public enum Intent {
        TERM_DEFINITION,
        AMOUNT_EXPLANATION,
        EXCLUSION_EXPLANATION,
        AMBIGUOUS,
        OUT_OF_SCOPE,
        UNKNOWN
    }
}