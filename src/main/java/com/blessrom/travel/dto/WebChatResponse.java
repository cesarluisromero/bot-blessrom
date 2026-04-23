package com.blessrom.travel.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class WebChatResponse {
    private String message;
    private List<ProductCard> products; // Productos sugeridos por la IA (clicables)
    private String sessionId;
    private String searchUrl;   // URL de búsqueda directa en el sitio web
    private String searchLabel; // Etiqueta del botón (ej: "Ver todos los polos")

    public WebChatResponse() {}

    public WebChatResponse(String message) {
        this.message = message;
    }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public List<ProductCard> getProducts() { return products; }
    public void setProducts(List<ProductCard> products) { this.products = products; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSearchUrl() { return searchUrl; }
    public void setSearchUrl(String searchUrl) { this.searchUrl = searchUrl; }

    public String getSearchLabel() { return searchLabel; }
    public void setSearchLabel(String searchLabel) { this.searchLabel = searchLabel; }
}
