package com.blessrom.travel.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class WebChatRequest {
    private String sessionId;
    private String message;
    private String currentProductId; 
    private String userId;   
    private String userName; 

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getCurrentProductId() { return currentProductId; }
    public void setCurrentProductId(String currentProductId) { this.currentProductId = currentProductId; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
}
