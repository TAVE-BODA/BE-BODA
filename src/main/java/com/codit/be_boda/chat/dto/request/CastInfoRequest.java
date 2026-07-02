package com.codit.be_boda.chat.dto.request;

import com.codit.be_boda.chat.type.CastInjuryPartType;
import com.codit.be_boda.chat.type.CastType;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 깁스 선택 시 들어오는 추가 정보
@Getter
@NoArgsConstructor
public class CastInfoRequest {

    private CastInjuryPartType castInjuryPartType;
    private CastType castType;
}