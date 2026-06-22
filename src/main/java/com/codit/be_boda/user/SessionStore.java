package com.codit.be_boda.user;

import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 인메모리 세션 저장소
// [현재] ConcurrentHashMap (프로토타입)
// [카카오 연동 후] UserRepository (JPA + PostgreSQL) 로 교체.. 하기.

@Component
public class SessionStore {

    private final Map<String, UserSession> store = new ConcurrentHashMap<>();

    public UserSession getOrCreate(String userId) {
        return store.computeIfAbsent(userId, id -> {
            UserSession s = new UserSession();
            s.setUserId(id);
            s.setCreatedAt(LocalDateTime.now());
            return s;
        });
    }

    public UserSession get(String userId) {
        return store.get(userId);
    }

    public boolean exists(String userId) {
        return userId != null && store.containsKey(userId);
    }

    public String newId() {
        return UUID.randomUUID().toString();
    }
}
