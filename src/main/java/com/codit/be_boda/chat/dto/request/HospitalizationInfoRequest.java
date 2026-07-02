package com.codit.be_boda.chat.dto.request;

import com.codit.be_boda.chat.type.HospitalType;
import com.codit.be_boda.chat.type.RoomType;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 입원 선택 시 들어오는 추가 정보
@Getter
@NoArgsConstructor
public class HospitalizationInfoRequest {

    private HospitalType hospitalType;
    private RoomType roomType;
    private Integer hospitalizedNights;
}