package com.blessrom.travel;

import com.blessrom.travel.client.SincronizadorClient;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;
import java.util.List;

@ApplicationScoped
public class ChatService {

    private static final Logger LOG = Logger.getLogger(ChatService.class);

    @Inject
    RedisDataSource redis;

    @Inject
    AgenteVentas agente;

    @Inject
    SincronizadorClient sincronizadorClient; // Inyectamos el cliente del microservicio

    @Transactional
    public String procesarMensaje(String message, String fromNumber) {
        String historialKey = "historial:" + fromNumber;

        // 1. Obtener historial (Método correcto en Quarkus Redis: lrange)
        // Traemos los últimos 10 mensajes para contexto
        List<String> historial = redis.list(String.class).lrange(historialKey, -10, -1);
        String historialStr = String.join("\n", historial);

        // 2. CONSULTA AL OTRO MICROSERVICIO (RAG)
        // Ya no hay SQL aquí. Le pedimos los productos relevantes al Sincronizador.
        String contextoProductos = sincronizadorClient.buscarProductos(message);

        LOG.info("📡 Solicitando productos al microservicio sincronizador...");
        LOG.info("📦 Respuesta recibida del Sincronizador: " + contextoProductos);
        // 3. Respuesta de la IA
        String respuesta = agente.responder(message, contextoProductos, historialStr);

        // 4. Guardar en Redis (Método correcto en Quarkus Redis: rpush)
        redis.list(String.class).rpush(historialKey, "User: " + message);
        redis.list(String.class).rpush(historialKey, "Bot: " + respuesta);

        return respuesta;
    }
}