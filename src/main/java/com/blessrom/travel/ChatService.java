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

    @Transactional
    public String procesarMensaje(String message, String fromNumber) {

        // 🔒 1. ZONA DE ADMINISTRADOR
        String miNumeroAdmin = "931635969"; // Recuerda dejar tu número real aquí de 9 dígitos
        String comandoAdmin = message.trim().toLowerCase();

        // Verificamos si el mensaje viene de ti
        if (fromNumber.endsWith(miNumeroAdmin)) {

            // 🟢 COMANDO: /clientes (Lista todos los números y resalta los calientes)
            if (comandoAdmin.equals("/clientes")) {
                try {
                    List<String> llaves = redis.key().keys("historial:*");
                    // Traemos la lista de los que tienen intención de compra
                    java.util.Set<String> leadsCalientes = redis.set(String.class).smembers("leads_calientes");

                    if (llaves == null || llaves.isEmpty()) {
                        return "⚠️ Aún no hay clientes en la memoria reciente.";
                    }

                    StringBuilder respuesta = new StringBuilder("📱 *Clientes Recientes:*\n\n");
                    for (String llave : llaves) {
                        String numero = llave.replace("historial:", "");
                        // Si el número está en la lista caliente, le ponemos fuego
                        if (leadsCalientes.contains(numero)) {
                            respuesta.append("🔥 *").append(numero).append("* (Alta intención)\n");
                        } else {
                            respuesta.append("👤 ").append(numero).append("\n");
                        }
                    }

                    respuesta.append("\n👉 Escribe `/historial NUMERO` para espiar.");
                    // Opcional: limpiar la lista caliente después de leerla para empezar fresco
                    // redis.key().del("leads_calientes");
                    return respuesta.toString();
                } catch (Exception e) {
                    return "⚠️ Error al buscar la lista de clientes.";
                }
            }

            // 🔵 COMANDO EXISTENTE: /historial NUMERO (Muestra la charla)
            if (comandoAdmin.startsWith("/historial")) {
                try {
                    String numeroCliente = message.split(" ")[1].trim();
                    List<String> historial = redis.list(String.class).lrange("historial:" + numeroCliente, -10, -1);

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

        String historialKey = "historial:" + fromNumber;

        // 1. Obtener historial (Método correcto en Quarkus Redis: lrange)
        // Traemos los últimos 10 mensajes para contexto
        List<String> historial = redis.list(String.class).lrange(historialKey, -10, -1);
        String historialStr = String.join("\n", historial);

        // 2. Memoria Semántica (El "cerebro" de gustos pasados)
        String memoriaPasada = buscarMemoriaSemantica(message, fromNumber);

        // 3. CONSULTA AL OTRO MICROSERVICIO (RAG)
        // Ya no hay SQL aquí. Le pedimos los productos relevantes al Sincronizador.
        String contextoProductos = sincronizadorClient.buscarProductos(message);

        LOG.info("📦 Respuesta recibida del Sincronizador: " + contextoProductos);

        //4. 🚨 RADAR DE VENTAS INTELIGENTE: Detecta plurales y variaciones
        if (PATRON_COMPRAS.matcher(message).matches()) {
            redis.set(String.class).sadd("leads_calientes", fromNumber);
            LOG.info("🔥 Lead caliente detectado: " + fromNumber);
        }

        // 5. Respuesta de la IA
        String respuesta = agente.responder(message, contextoProductos, memoriaPasada, historialStr);

        // 6. Guardar en Redis (Método correcto en Quarkus Redis: rpush)
        redis.list(String.class).rpush(historialKey, "User: " + message);
        redis.list(String.class).rpush(historialKey, "Bot: " + respuesta);

        // 7. Guardar Memoria a largo plazo (Redis Vector)
        guardarMemoriaSemantica(fromNumber, "Cliente preguntó: " + message + " | Bot respondió: " + respuesta);

        return respuesta;
    }

    private void guardarMemoriaSemantica(String phone, String texto) {
        // 1. Creamos el segmento de texto adjuntando el teléfono como metadato
        TextSegment segmento = TextSegment.from(texto, Metadata.metadata("customer_id", phone));

        // 2. Generamos el vector
        dev.langchain4j.data.embedding.Embedding embedding = embeddingModel.embed(segmento).content();

        // 3. Guardamos. La extensión hace el HSET y convierte los bytes por nosotros
        memoriaStore.add(embedding, segmento);
    }

    private String buscarMemoriaSemantica(String query, String phone) {
        try {
            // 1. Generamos el vector de la pregunta
            dev.langchain4j.data.embedding.Embedding queryEmbedding = embeddingModel.embed(query).content();

            // 2. Preparamos la búsqueda filtrando solo por el número de este cliente
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(3)
                    .filter(MetadataFilterBuilder.metadataKey("customer_id").isEqualTo(phone))
                    .build();

            // 3. Ejecutamos la búsqueda
            var resultados = memoriaStore.search(request).matches();

            if (resultados.isEmpty()) {
                return "Sin memoria previa relevante.";
            }

            // 4. Extraemos y unimos los textos encontrados
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