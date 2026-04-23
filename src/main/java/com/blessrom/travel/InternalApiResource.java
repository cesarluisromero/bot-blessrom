package com.blessrom.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;

/**
 * API interna para que el dashboard web (Botbless) pueda leer datos
 * del bot y enviar mensajes. Solo accesible desde localhost vía Nginx config.
 * Soporta filtrado multi-tenant por phoneNumberId.
 */
@Path("/api/internal")
@Produces(MediaType.APPLICATION_JSON)
public class InternalApiResource {

    private static final Logger LOG = Logger.getLogger(InternalApiResource.class);

    @Inject
    RedisDataSource redis;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    BusinessConfigClient businessConfigClient;

    /**
     * Estadísticas generales del bot, opcionalmente filtradas por negocio.
     */
    @GET
    @Path("/stats")
    public Response getStats(@QueryParam("phoneNumberId") String phoneNumberId) {
        try {
            String keyPattern = (phoneNumberId != null && !phoneNumberId.isBlank())
                    ? "historial:" + phoneNumberId + ":*"
                    : "historial:*";
            String leadsKey = (phoneNumberId != null && !phoneNumberId.isBlank())
                    ? "leads_calientes:" + phoneNumberId
                    : "leads_calientes";

            List<String> keys = redis.key().keys(keyPattern);
            Set<String> hotLeads = redis.set(String.class).smembers(leadsKey);

            int totalConversations = keys != null ? keys.size() : 0;
            int totalHotLeads = hotLeads != null ? hotLeads.size() : 0;

            int totalMessages = 0;
            if (keys != null) {
                for (String key : keys) {
                    totalMessages += (int) redis.list(String.class).llen(key);
                }
            }

            ObjectNode result = objectMapper.createObjectNode();
            result.put("totalConversations", totalConversations);
            result.put("totalHotLeads", totalHotLeads);
            result.put("totalMessages", totalMessages);

            return Response.ok(result).build();
        } catch (Exception e) {
            LOG.error("Error obteniendo stats", e);
            return Response.serverError().entity("{\"error\": \"Error obteniendo estadísticas.\"}").build();
        }
    }

    /**
     * Lista de todas las conversaciones activas, opcionalmente filtradas por negocio.
     */
    @GET
    @Path("/conversations")
    public Response getConversations(@QueryParam("phoneNumberId") String phoneNumberId) {
        try {
            String keyPattern = (phoneNumberId != null && !phoneNumberId.isBlank())
                    ? "historial:" + phoneNumberId + ":*"
                    : "historial:*";
            String leadsKey = (phoneNumberId != null && !phoneNumberId.isBlank())
                    ? "leads_calientes:" + phoneNumberId
                    : "leads_calientes";

            List<String> keys = redis.key().keys(keyPattern);
            Set<String> hotLeads = redis.set(String.class).smembers(leadsKey);

            ArrayNode conversations = objectMapper.createArrayNode();

            if (keys != null) {
                for (String key : keys) {
                    // Extraer el teléfono del cliente (última parte de la clave)
                    String[] parts = key.split(":");
                    String phone = parts[parts.length - 1];

                    List<String> messages = redis.list(String.class).lrange(key, -2, -1);
                    String lastMessage = messages != null && !messages.isEmpty()
                            ? messages.get(messages.size() - 1)
                            : "(sin mensajes)";
                    long messageCount = redis.list(String.class).llen(key);
                    boolean isHot = hotLeads != null && hotLeads.contains(phone);

                    ObjectNode conv = objectMapper.createObjectNode();
                    conv.put("phone", phone);
                    conv.put("lastMessage", lastMessage);
                    conv.put("messageCount", messageCount);
                    conv.put("isHotLead", isHot);
                    conversations.add(conv);
                }
            }

            return Response.ok(conversations).build();
        } catch (Exception e) {
            LOG.error("Error obteniendo conversaciones", e);
            return Response.serverError().entity("{\"error\": \"Error obteniendo conversaciones.\"}").build();
        }
    }

    /**
     * Historial completo de un número específico.
     */
    @GET
    @Path("/history/{phone}")
    public Response getHistory(
            @PathParam("phone") String phone,
            @QueryParam("phoneNumberId") String phoneNumberId) {
        try {
            String key = (phoneNumberId != null && !phoneNumberId.isBlank())
                    ? "historial:" + phoneNumberId + ":" + phone
                    : "historial:" + phone;

            List<String> messages = redis.list(String.class).lrange(key, 0, -1);

            ArrayNode history = objectMapper.createArrayNode();
            if (messages != null) {
                for (String msg : messages) {
                    ObjectNode entry = objectMapper.createObjectNode();
                    if (msg.startsWith("User: ")) {
                        entry.put("sender", "user");
                        entry.put("text", msg.substring(6));
                    } else if (msg.startsWith("Bot: ")) {
                        entry.put("sender", "bot");
                        entry.put("text", msg.substring(5));
                    } else {
                        entry.put("sender", "system");
                        entry.put("text", msg);
                    }
                    history.add(entry);
                }
            }

            return Response.ok(history).build();
        } catch (Exception e) {
            LOG.error("Error obteniendo historial de " + phone, e);
            return Response.serverError().entity("{\"error\": \"Error obteniendo historial.\"}").build();
        }
    }

    /**
     * Enviar un mensaje manual a un número de WhatsApp.
     * Ahora requiere phoneNumberId para saber con qué credenciales enviar.
     */
    @POST
    @Path("/send")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response sendMessage(String payload) {
        try {
            var node = objectMapper.readTree(payload);
            String phone = node.get("phone").asText();
            String message = node.get("message").asText();
            String phoneNumberId = node.has("phoneNumberId") ? node.get("phoneNumberId").asText() : null;

            String accessToken;
            String sendPhoneId;

            if (phoneNumberId != null && !phoneNumberId.isBlank()) {
                // Multi-tenant: buscar credenciales del negocio
                BusinessConfigClient.BusinessConfig config = businessConfigClient.getConfig(phoneNumberId);
                if (config == null) {
                    return Response.status(404).entity("{\"error\": \"Negocio no encontrado.\"}").build();
                }
                accessToken = config.accessToken();
                sendPhoneId = config.phoneNumberId();
            } else {
                // Fallback legacy: usar system properties
                sendPhoneId = System.getProperty("whatsapp.phone-number-id");
                accessToken = System.getProperty("whatsapp.access-token");
            }

            if (sendPhoneId == null || accessToken == null) {
                return Response.serverError()
                        .entity("{\"error\": \"Credenciales de WhatsApp no configuradas.\"}")
                        .build();
            }

            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.put("messaging_product", "whatsapp");
            rootNode.put("recipient_type", "individual");
            rootNode.put("to", phone);
            rootNode.put("type", "text");
            rootNode.withObject("/text").put("body", message);

            String jsonBody = objectMapper.writeValueAsString(rootNode);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.facebook.com/v18.0/" + sendPhoneId + "/messages"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            // Guardar en historial con namespace
            String historialKey = (phoneNumberId != null && !phoneNumberId.isBlank())
                    ? "historial:" + phoneNumberId + ":" + phone
                    : "historial:" + phone;
            redis.list(String.class).rpush(historialKey, "Bot: " + message);

            if (response.statusCode() == 200) {
                LOG.info("✅ Mensaje manual enviado a: " + phone);
                return Response.ok("{\"status\": \"success\", \"message\": \"Mensaje enviado.\"}").build();
            } else {
                LOG.error("❌ Error de Meta: " + response.body());
                return Response.status(response.statusCode())
                        .entity("{\"error\": \"Error de Meta: " + response.statusCode() + "\"}")
                        .build();
            }

        } catch (Exception e) {
            LOG.error("Error enviando mensaje manual", e);
            return Response.serverError().entity("{\"error\": \"Error enviando mensaje.\"}").build();
        }
    }
}
