package com.blessrom.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.fasterxml.jackson.databind.node.ObjectNode;

@jakarta.ws.rs.Path("/whatsapp")
public class WhatsAppResource {

    @Inject ChatService chatService;
    @Inject BusinessConfigClient businessConfigClient;

    private static final Logger LOG = Logger.getLogger(WhatsAppResource.class);

    @ConfigProperty(name = "whatsapp.verify-token", defaultValue = "blessrom_token_2026")
    String verifyToken;

    @Inject
    ObjectMapper objectMapper;

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response verify(@QueryParam("hub.mode") String mode,
                           @QueryParam("hub.verify_token") String token,
                           @QueryParam("hub.challenge") String challenge) {

        LOG.info("Intento de validación: token recibido = " + token);

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            LOG.info("Validación exitosa!");
            return Response.ok(challenge).build();
        }

        LOG.warn("Validación fallida: el token no coincide");
        return Response.status(Response.Status.FORBIDDEN).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receiveMessage(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);

            if (node.has("entry") && node.get("entry").get(0).get("changes").get(0).get("value").has("messages")) {

                JsonNode valueNode = node.get("entry").get(0).get("changes").get(0).get("value");
                JsonNode messageNode = valueNode.get("messages").get(0);

                // Extraer datos del mensaje
                String phone = messageNode.get("from").asText();
                String message = messageNode.get("text").get("body").asText();

                // MULTI-TENANT: Extraer el phone_number_id del metadata
                String phoneNumberId = valueNode.get("metadata").get("phone_number_id").asText();
                LOG.info("📨 Mensaje de " + phone + " para negocio (phoneNumberId): " + phoneNumberId);

                // Resolver la configuración del negocio
                BusinessConfigClient.BusinessConfig config = businessConfigClient.getConfig(phoneNumberId);

                if (config == null) {
                    LOG.warn("⚠️ No se encontró configuración para phoneNumberId: " + phoneNumberId + ". Ignorando mensaje.");
                    return Response.ok().build();
                }

                // Procesar con la IA usando la config del negocio
                String respuestaIA = chatService.procesarMensaje(message, phone, config);

                // Enviar respuesta con el accessToken del negocio
                enviarMensajeWhatsApp(phone, respuestaIA, phoneNumberId, config.accessToken());

                LOG.info("✅ Respuesta enviada a " + phone + " (Negocio: " + config.name() + ")");
            }

            return Response.ok().build();
        } catch (Exception e) {
            LOG.error("Error procesando mensaje", e);
            return Response.serverError().build();
        }
    }

    /**
     * Enviar mensaje a WhatsApp usando las credenciales del negocio.
     */
    private void enviarMensajeWhatsApp(String toNumber, String text, String phoneNumberId, String accessToken) {
        try {
            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.put("messaging_product", "whatsapp");
            rootNode.put("recipient_type", "individual");
            rootNode.put("to", toNumber);
            rootNode.put("type", "text");
            rootNode.withObject("/text").put("body", text);

            String jsonBody = objectMapper.writeValueAsString(rootNode);

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.facebook.com/v18.0/" + phoneNumberId + "/messages"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                LOG.info("✅ Mensaje entregado a Meta para: " + toNumber);
            } else {
                LOG.error("❌ Error de Meta (" + response.statusCode() + "): " + response.body());
            }

        } catch (Exception e) {
            LOG.error("🚨 Error crítico enviando a WhatsApp", e);
        }
    }
}