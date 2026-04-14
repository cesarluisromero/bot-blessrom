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

    private static final Logger LOG = Logger.getLogger(WhatsAppResource.class);

    @ConfigProperty(name = "whatsapp.verify-token", defaultValue = "blessrom_token_2026")
    String verifyToken;

    @Inject
    ObjectMapper objectMapper;

    @GET
    @Produces(MediaType.TEXT_PLAIN) // <--- Fuerza a que sea texto plano
    public Response verify(@QueryParam("hub.mode") String mode,
                           @QueryParam("hub.verify_token") String token,
                           @QueryParam("hub.challenge") String challenge) {

        LOG.info("Intento de validación: token recibido = " + token);

        // Usamos la variable que configuramos con @ConfigProperty
        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            LOG.info("Validación exitosa!");
            return Response.ok(challenge).build(); // Devuelve el número que Meta envió
        }

        LOG.warn("Validación fallida: el token no coincide");
        return Response.status(Response.Status.FORBIDDEN).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receiveMessage(String payload) {
        try {
            // 1. Parsear el JSON de Meta
            JsonNode node = objectMapper.readTree(payload);


            // Verificamos si es un mensaje de texto (Meta envía estados y otros ruidos)
            if (node.has("entry") && node.get("entry").get(0).get("changes").get(0).get("value").has("messages")) {

                JsonNode messageNode = node.get("entry").get(0).get("changes").get(0).get("value").get("messages").get(0);

                // EXTRAEMOS EL TELÉFONO REAL Y EL MENSAJE REAL
                String phone = messageNode.get("from").asText(); // Ej: "51949545854"
                String message = messageNode.get("text").get("body").asText(); // Ej: "Hola, quiero viajar"

                // 2. Procesar con la IA
                String respuestaIA = chatService.procesarMensaje(message, phone);

                // 3. ENVIAR RESPUESTA A WHATSAPP (Lógica que faltaba)
                enviarMensajeWhatsApp(phone, respuestaIA);

                LOG.info("Respuesta enviada con éxito a: " + phone);
            }

            return Response.ok().build();
        } catch (Exception e) {
            LOG.error("Error procesando mensaje", e);
            return Response.serverError().build();
        }
    }

    // Método auxiliar para responder a Meta
    private void enviarMensajeWhatsApp(String toNumber, String text) {
        try {
            // 1. Recuperamos las credenciales que pasamos por el comando nohup (-D)
            String phoneId = System.getProperty("whatsapp.phone-number-id");
            String accessToken = System.getProperty("whatsapp.access-token");

            // 2. Construimos el cuerpo del mensaje en JSON usando Jackson
            ObjectNode rootNode = objectMapper.createObjectNode();
            rootNode.put("messaging_product", "whatsapp");
            rootNode.put("recipient_type", "individual");
            rootNode.put("to", toNumber);
            rootNode.put("type", "text");
            rootNode.withObject("/text").put("body", text);

            String jsonBody = objectMapper.writeValueAsString(rootNode);

            // 3. Preparamos la petición HTTP hacia Meta
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://graph.facebook.com/v18.0/" + phoneId + "/messages"))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                    .build();

            // 4. Enviamos y revisamos la respuesta
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