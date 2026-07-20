package com.codit.be_boda.global.support;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

//보험사명 정규화 / 추출기
// 마이페이지는 보험사를 카테고리 키로 쓰는데, 보험사명은
// 증권: LLM이 extracted_data.companyName 으로 추출 ("삼성생명보험(주)" 등 표기 흔들림)
// 약관: 본문에서 직접 탐지로 들어오기 때문에 표기를 하나로 모아주는 정규화가 필요하다.
// canonical()은 알려진 보험사명 목록에 매칭시켜 대표 표기로 환원한다.
// 예) "삼성생명보험(주)" → "삼성생명", "삼성화재해상보험" → "삼성화재"
// (단순히 접미사만 떼면 삼성생명/삼성화재가 "삼성"으로 합쳐지므로 목록 매칭 방식을 쓴다)
@Component
public class InsurerNameResolver {

    public static final String UNKNOWN = "보험사 정보 없음";

    // 긴 이름이 먼저 매칭되도록 길이 내림차순 정렬해서 사용
    private static final List<String> KNOWN_INSURERS = List.of(
            "삼성생명", "삼성화재",
            "한화생명", "한화손해보험", "한화손보",
            "교보생명", "교보라이프플래닛",
            "흥국생명", "흥국화재",
            "동양생명",
            "신한라이프", "신한EZ손해보험",
            "KB손해보험", "KB라이프",
            "DB손해보험", "DB생명",
            "현대해상", "푸본현대생명",
            "메리츠화재",
            "롯데손해보험",
            "미래에셋생명",
            "NH농협생명", "NH농협손해보험",
            "ABL생명", "AIA생명", "라이나생명", "처브라이프",
            "하나생명", "하나손해보험",
            "MG손해보험", "캐롯손해보험", "카카오페이손해보험",
            "IBK연금보험", "코리안리", "서울보증보험",
            "동부화재", "LIG손해보험"
    );

    private static final List<String> MATCH_ORDER = KNOWN_INSURERS.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .toList();

    // 보험사명을 카테고리 키(대표 표기)로 환원
   // 알려진 보험사와 매칭되지 않으면 공백/법인표기만 제거해서 그대로 사용
    public String canonical(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return UNKNOWN;
        }

        String normalized = strip(rawName);
        if (normalized.isBlank()) {
            return UNKNOWN;
        }

        for (String known : MATCH_ORDER) {
            if (normalized.contains(known)) {
                return known;
            }
        }
        return normalized;
    }

    // 약관 본문에서 보험사명 탐지
     // 표지에 보험사명이 나오므로 가장 먼저 등장하는 보험사를 채택한다.
    public String detectFromText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized = strip(text);

        String found = null;
        int foundIndex = Integer.MAX_VALUE;

        for (String known : MATCH_ORDER) {
            int index = normalized.indexOf(known);
            if (index >= 0 && index < foundIndex) {
                foundIndex = index;
                found = known;
            }
        }
        return found;
    }

    // 공백 / 괄호 / 법인 표기 제거
    private String strip(String value) {
        return value
                .replaceAll("주식회사", "")
                .replaceAll("[\\s()（）㈜]", "");
    }
}
