package com.codit.be_boda.chat.type;

// 입원 선택 시: 어떤 병실을 이용했는지
public enum RoomType {
    PRIVATE_ROOM,     // 1인실
    TWO_THREE_ROOM,   // 2,3인실
    GENERAL_ROOM      // 일반 병실, 4인실 이상
}