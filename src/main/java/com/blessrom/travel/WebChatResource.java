package com.blessrom.travel;

import com.blessrom.travel.client.SincronizadorClient;
import com.blessrom.travel.dto.ProductCard;
import com.blessrom.travel.dto.WebChatRequest;
import com.blessrom.travel.dto.WebChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Path("/web-chat")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class WebChatResource {

    private static final Logger LOG = Logger.getLogger(WebChatResource.class);

    // Rate limiting: máximo 30 mensajes por sesión
    private static final int MAX_MESSAGES_PER_SESSION = 30;
    private final Map<String, Integer> sessionMessageCount = new ConcurrentHashMap<>();

    // Patrón para detectar menciones de productos o intención de búsqueda
    private static final Pattern PRODUCT_MENTION_PATTERN = Pattern.compile(
            "(?i)(vestido|polo|blusa|falda|pantalón|short|camisa|chaqueta|zapato|cartera|bolso|accesorio|ver|mostrar|tienes|hay|buscar|negro|blanco|rojo|azul|verde|amarillo|marrón|gris)");

    @Inject
    ChatService chatService;

    @Inject
    SincronizadorClient sincronizadorClient;

    @Inject
    ObjectMapper objectMapper;

    @POST
    public Response chat(WebChatRequest request) {
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return Response.status(400).entity(Map.of("error", "Mensaje vacío")).build();
        }

        // 1. Identificación de sesión y usuario
        String sessionId = request.getSessionId();
        String userId = request.getUserId();
        String userName = request.getUserName();
        
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = java.util.UUID.randomUUID().toString();
        }

        // 2. Rate limiting por sesión (basado en sessionId original)
        int count = sessionMessageCount.getOrDefault(sessionId, 0);
        if (count >= MAX_MESSAGES_PER_SESSION) {
            WebChatResponse limitResponse = new WebChatResponse(
                    "😊 Has alcanzado el límite de mensajes para esta sesión. Para seguir conversando, recarga la página.");
            limitResponse.setSessionId(sessionId);
            return Response.ok(limitResponse).build();
        }
        sessionMessageCount.put(sessionId, count + 1);

        try {
            // 3. CONFIGURACIÓN INDEPENDIENTE PARA WEB (Sin WhatsApp)
            BusinessConfigClient.BusinessConfig webConfig = new BusinessConfigClient.BusinessConfig(
                0L, 
                "Blessrom Web Assistant", 
                "", 
                "Eres el asistente virtual experto de Blessrom (blessrom.com). Tu misión es ayudar a los visitantes a encontrar ropa, vestidos y polos de alta calidad. Eres amable, profesional y usas un tono premium. " + 
                (userName != null && !userName.isBlank() ? "El usuario se llama " + userName + ". Salúdalo por su nombre." : ""),
                "Catálogo Web Blessrom", 
                true, 
                "admin",
                "blessrom-web"
            );

            // 4. Determinar ID de conversación para el historial en Redis
            String redisSessionId = (userId != null && !userId.isBlank()) 
                    ? "web-user:" + userId 
                    : "web-session:" + sessionId;

            // 5. Procesar el mensaje
            String aiResponse = chatService.procesarMensaje(message, redisSessionId, webConfig);

            // 6. Buscar productos relevantes para mostrar como tarjetas
            List<ProductCard> productCards = new ArrayList<>();
            boolean intentMatch = PRODUCT_MENTION_PATTERN.matcher(message).find() || PRODUCT_MENTION_PATTERN.matcher(aiResponse).find();
            
            if (intentMatch) {
                LOG.info("🔍 Intención de búsqueda detectada para: " + message);
                productCards = searchRelatedProducts(message, 4);
                LOG.info("📦 Tarjetas encontradas: " + productCards.size());
            }

            WebChatResponse response = new WebChatResponse(aiResponse);
            response.setSessionId(sessionId);
            response.setProducts(productCards);

            // 7. Generar enlace de búsqueda directa en el sitio web si hay intención de producto
            if (!productCards.isEmpty()) {
                String encodedQuery = URLEncoder.encode(message, StandardCharsets.UTF_8);
                response.setSearchUrl("https://blessrom.com/?s=" + encodedQuery);
                response.setSearchLabel("Ver todos los resultados en la tienda 🛍️");
            }

            return Response.ok(response).build();

        } catch (Exception e) {
            LOG.error("❌ Error en web chat: " + e.getMessage(), e);
            WebChatResponse errorResponse = new WebChatResponse(
                    "Lo siento, tuve un problema técnico. ¿Puedes intentarlo de nuevo?");
            errorResponse.setSessionId(sessionId);
            return Response.ok(errorResponse).build();
        }
    }

    /**
     * Busca productos relevantes a la consulta del usuario delegando al microservicio de búsqueda.
     */
    private List<ProductCard> searchRelatedProducts(String query, int limit) {
        List<ProductCard> cards = new ArrayList<>();
        try {
            String json = sincronizadorClient.buscarTarjetasJson(query, limit);
            JsonNode root = objectMapper.readTree(json);
            JsonNode results = root.get("results");

            if (results != null && results.isArray()) {
                for (JsonNode node : results) {
                    cards.add(new ProductCard(
                            node.has("id") ? node.get("id").asText() : "",
                            node.has("name") ? node.get("name").asText() : "",
                            node.has("imageUrl") ? node.get("imageUrl").asText() : "",
                            node.has("url") ? node.get("url").asText() : "",
                            node.has("price") ? node.get("price").asDouble() : 0));
                }
            }
        } catch (Exception e) {
            LOG.warn("⚠️ Error recuperando tarjetas vía SincronizadorClient: " + e.getMessage());
        }
        return cards;
    }
}
