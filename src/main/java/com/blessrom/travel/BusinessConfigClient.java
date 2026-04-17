package com.blessrom.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cliente que consulta al Web Backend (puerto 8083) para obtener
 * la configuración de un negocio por su phone_number_id.
 * Incluye caché en memoria de 5 minutos para evitar latencia.
 */
@ApplicationScoped
public class BusinessConfigClient {

    private static final Logger LOG = Logger.getLogger(BusinessConfigClient.class);

    @ConfigProperty(name = "botbless.web-api-url", defaultValue = "http://localhost:8083")
    String webApiUrl;

    @ConfigProperty(name = "botbless.internal-secret", defaultValue = "botbless-secret-2026")
    String internalSecret;

    @Inject
    ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    // Caché simple: phoneNumberId -> { config, timestamp }
    private final Map<String, CachedConfig> cache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutos

    /**
     * Obtiene la configuración de un negocio. Usa caché si está fresca.
     */
    public BusinessConfig getConfig(String phoneNumberId) {
        // Verificar caché
        CachedConfig cached = cache.get(phoneNumberId);
        if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
            return cached.config;
        }

        // Consultar al Web Backend
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(webApiUrl + "/api/internal/business/by-phone/" + phoneNumberId))
                    .header("X-Internal-Secret", internalSecret)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                BusinessConfig config = new BusinessConfig(
                        node.get("id").asLong(),
                        node.get("name").asText(""),
                        node.get("accessToken").asText(""),
                        node.get("botPrompt").asText(""),
                        node.get("productCatalog").asText(""),
                        node.get("hasRag").asBoolean(false),
                        node.get("ownerUsername").asText(""),
                        node.get("phoneNumberId").asText("")
                );

                // Guardar en caché
                cache.put(phoneNumberId, new CachedConfig(config, System.currentTimeMillis()));
                LOG.info("📋 Config cargada para negocio: " + config.name());
                return config;
            } else {
                LOG.warn("⚠️ Negocio no encontrado para phoneNumberId: " + phoneNumberId + " (HTTP " + response.statusCode() + ")");
                return null;
            }
        } catch (Exception e) {
            LOG.error("❌ Error consultando config de negocio: " + e.getMessage());
            return null;
        }
    }

    /**
     * Invalida la caché de un negocio (útil cuando se actualiza desde la web).
     */
    public void invalidateCache(String phoneNumberId) {
        cache.remove(phoneNumberId);
    }

    // Record de configuración de negocio
    public record BusinessConfig(
            long id,
            String name,
            String accessToken,
            String botPrompt,
            String productCatalog,
            boolean hasRag,
            String ownerUsername,
            String phoneNumberId
    ) {}

    // Wrapper para caché con timestamp
    private record CachedConfig(BusinessConfig config, long timestamp) {}
}
