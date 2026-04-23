package com.blessrom.travel;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import com.blessrom.travel.client.SincronizadorClient;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.regex.Pattern;
import java.util.List;

@ApplicationScoped
public class ChatService {

    private static final Logger LOG = Logger.getLogger(ChatService.class);

    // Radar Blindado: Atrapa errores ortográficos, sufijos (comprarlo, yapearte) y sinónimos
    private static final Pattern PATRON_COMPRAS = Pattern.compile(
            "(?i).*\\b(prec[ií]o[s]?|presio[s]?|costo|vale|compra[a-z]*|yape[a-z]*|plin|plim|talla[s]?|env[ií]o[s]?|enviar|cu[aá]nto|k[u]?anto|quiero|pago|pagar|cuenta|transfer[a-z]*)\\b.*"
    );

    @Inject
    RedisDataSource redis;

    @Inject
    AgenteVentas agente;

    @Inject
    SincronizadorClient sincronizadorClient;

    @Inject
    EmbeddingModel embeddingModel;

    @Inject
    EmbeddingStore<TextSegment> memoriaStore;

    /**
     * Procesa un mensaje con configuración multi-tenant.
     */
    @Transactional
    public String procesarMensaje(String message, String fromNumber, BusinessConfigClient.BusinessConfig config) {

        String phoneNumberId = config.phoneNumberId();
        
        // 🔒 ZONA DE ADMINISTRADOR (usa el número del admin general)
        String miNumeroAdmin = "931635969";
        String comandoAdmin = message.trim().toLowerCase();

        if (fromNumber.endsWith(miNumeroAdmin)) {
            if (comandoAdmin.equals("/clientes")) {
                try {
                    List<String> llaves = redis.key().keys("historial:" + phoneNumberId + ":*");
                    java.util.Set<String> leadsCalientes = redis.set(String.class).smembers("leads_calientes:" + phoneNumberId);

                    if (llaves == null || llaves.isEmpty()) {
                        return "⚠️ Aún no hay clientes para " + config.name() + ".";
                    }

                    StringBuilder respuesta = new StringBuilder("📱 *Clientes de " + config.name() + ":*\n\n");
                    for (String llave : llaves) {
                        String numero = llave.replace("historial:" + phoneNumberId + ":", "");
                        if (leadsCalientes != null && leadsCalientes.contains(numero)) {
                            respuesta.append("🔥 *").append(numero).append("* (Alta intención)\n");
                        } else {
                            respuesta.append("👤 ").append(numero).append("\n");
                        }
                    }
                    respuesta.append("\n👉 Escribe `/historial NUMERO` para espiar.");
                    return respuesta.toString();
                } catch (Exception e) {
                    return "⚠️ Error al buscar la lista de clientes.";
                }
            }

            if (comandoAdmin.startsWith("/historial")) {
                try {
                    String numeroCliente = message.split(" ")[1].trim();
                    List<String> historial = redis.list(String.class).lrange("historial:" + phoneNumberId + ":" + numeroCliente, -10, -1);

                    if (historial == null || historial.isEmpty()) {
                        return "⚠️ No encontré conversaciones para: " + numeroCliente;
                    }
                    return "🕵️‍♂️ *Historial de " + numeroCliente + "*:\n\n" + String.join("\n", historial);
                } catch (Exception e) {
                    return "⚠️ Error. Usa el formato exacto: /historial NUMERO";
                }
            }
        }
        // fin de zona admin

        // Clave Redis con namespace multi-tenant
        String historialKey = "historial:" + phoneNumberId + ":" + fromNumber;

        // 1. Obtener historial
        List<String> historial = redis.list(String.class).lrange(historialKey, -10, -1);
        String historialStr = String.join("\n", historial);

        // 2. Memoria Semántica
        String memoriaPasada = buscarMemoriaSemantica(message, fromNumber);

        // 3. OBTENER PRODUCTOS según el tipo de negocio
        String contextoProductos;
        if (config.hasRag()) {
            // Blessrom C&M: usa el pipeline RAG (Sincronizador + Weaviate)
            contextoProductos = sincronizadorClient.buscarProductos(message);
            LOG.info("📦 Productos RAG para " + config.name() + ": " + contextoProductos);
        } else if (config.productCatalog() != null && !config.productCatalog().isBlank()) {
            // Negocio con catálogo JSON subido por admin
            contextoProductos = config.productCatalog();
            LOG.info("📋 Catálogo JSON para " + config.name());
        } else {
            // Negocio puramente conversacional, sin catálogo
            contextoProductos = "Sin catálogo de productos disponible.";
        }

        // 4. Radar de ventas
        if (PATRON_COMPRAS.matcher(message).matches()) {
            redis.set(String.class).sadd("leads_calientes:" + phoneNumberId, fromNumber);
            LOG.info("🔥 Lead caliente: " + fromNumber + " (Negocio: " + config.name() + ")");
        }

        // 5. Respuesta de la IA con prompt dinámico del negocio
        String botPrompt = config.botPrompt();
        if (botPrompt == null || botPrompt.isBlank()) {
            botPrompt = "Eres un asistente virtual profesional. Ayuda al cliente con sus consultas.";
        }
        
        String respuesta = agente.responder(message, botPrompt, contextoProductos, memoriaPasada, historialStr);

        // 6. Guardar en Redis con namespace
        redis.list(String.class).rpush(historialKey, "User: " + message);
        redis.list(String.class).rpush(historialKey, "Bot: " + respuesta);

        // 7. Guardar Memoria a largo plazo
        guardarMemoriaSemantica(fromNumber, "Cliente preguntó: " + message + " | Bot respondió: " + respuesta);

        return respuesta;
    }

    private void guardarMemoriaSemantica(String phone, String texto) {
        TextSegment segmento = TextSegment.from(texto, Metadata.metadata("customer_id", phone));
        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(segmento).content();
        memoriaStore.add(embedding, segmento);
    }

    private String buscarMemoriaSemantica(String query, String phone) {
        try {
            dev.langchain4j.data.embedding.Embedding queryEmbedding = embeddingModel.embed(query).content();

            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(3)
                    // .filter(MetadataFilterBuilder.metadataKey("customer_id").isEqualTo(phone)) // Desactivado por limitación de driver en modo nativo
                    .build();

            var resultados = memoriaStore.search(request).matches();

            if (resultados.isEmpty()) {
                return "Sin memoria previa relevante.";
            }

            StringBuilder memorias = new StringBuilder();
            for (var match : resultados) {
                memorias.append("- ").append(match.embedded().text()).append("\n");
            }
            return memorias.toString();

        } catch (Exception e) {
            LOG.warn("No se pudo recuperar la memoria semántica: " + e.getMessage());
            return "";
        }
    }

    private byte[] toByteArray(float[] floats) {
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats) {
            buffer.putFloat(f);
        }
        return buffer.array();
    }
}