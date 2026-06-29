package com.docgen.agent;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentSessionStore {

    private final ConcurrentHashMap<String, List<Map<String, Object>>> sessions = new ConcurrentHashMap<>();

    public List<Map<String, Object>> getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, ignored -> new ArrayList<>());
    }

    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }
}
