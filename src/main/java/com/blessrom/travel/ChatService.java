package com.blessrom.travel;

import com.blessrom.travel.entidades.Cliente;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.redis.datasource.RedisDataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;
import java.util.stream.Collectors;

@ApplicationScoped
public class ChatService {

    @Inject
    RedisDataSource redis;

    @Inject
    AgenteVentas agente;

    @Inject
    ObjectMapper objectMapper;

    @Inject
    @io.quarkus.hibernate.orm.PersistenceUnit("web")
    EntityManager emWeb;

    @Transactional
    public String procesarFlujo(String phone, String userMsg) {
        var redisValue = redis.value(String.class);

        // 1. Buscar en Redis, si no en DB
        String cached = redisValue.get("session:" + phone);
        Cliente cliente = (cached != null) ? deserialize(cached) : Cliente.find("telefono", phone).firstResult();

        if (cliente == null) {
            cliente = new Cliente();
            cliente.telefono = phone;
            cliente.historial = "[]";
            cliente.persist();
        }

        // --- CAMBIO AQUÍ: Obtenemos datos reales de la DB ---
        String catalogoReal = obtenerCatalogoDesdeBD();

        // 2. Obtener respuesta de IA
        String respuesta = agente.responder(userMsg, catalogoReal, cliente.historial);

        // 3. Actualizar Historial y Sincronizar
        cliente.historial = actualizarHistorial(cliente.historial, userMsg, respuesta);
        redisValue.setex("session:" + phone, 3600, serialize(cliente));

        return respuesta;
    }

    private String serialize(Cliente cliente) {
        try {
            return objectMapper.writeValueAsString(cliente);
        } catch (Exception e) {
            throw new RuntimeException("Error al serializar cliente", e);
        }
    }

    private Cliente deserialize(String json) {
        try {
            return objectMapper.readValue(json, Cliente.class);
        } catch (Exception e) {
            throw new RuntimeException("Error al deserializar cliente", e);
        }
    }

    private String actualizarHistorial(String historialActual, String userMsg, String aiReply) {
        try {
            List<Map<String, String>> mensajes;

            // 1. Si el historial está vacío o es nulo, inicializamos una lista nueva
            if (historialActual == null || historialActual.isEmpty() || historialActual.equals("[]")) {
                mensajes = new ArrayList<>();
            } else {
                // 2. Si ya tiene datos, deserializamos el JSON a una Lista de Mapas
                mensajes = objectMapper.readValue(historialActual, new TypeReference<List<Map<String, String>>>() {});
            }

            // 3. Añadimos el nuevo par de mensajes (Usuario y Asistente)
            mensajes.add(Map.of("role", "user", "content", userMsg));
            mensajes.add(Map.of("role", "assistant", "content", aiReply));

            // 4. Mantenemos el historial corto para ahorrar tokens (Opcional: solo los últimos 10 mensajes)
            if (mensajes.size() > 10) {
                mensajes = mensajes.subList(mensajes.size() - 10, mensajes.size());
            }

            // 5. Convertimos de nuevo a String JSON para persistir
            return objectMapper.writeValueAsString(mensajes);

        } catch (Exception e) {
            // En caso de error de formato, reseteamos el historial con la interacción actual para no romper el flujo
            return "[{\"role\":\"user\", \"content\":\"" + userMsg + "\"}, {\"role\":\"assistant\", \"content\":\"" + aiReply + "\"}]";
        }
    }

    private String obtenerCatalogoDesdeBD() {
        // CAMBIO CLAVE: Añadimos una subconsulta para filtrar solo los que dicen 'instock'
        String sql = "SELECT p.`post_title`, " +
                "MAX(CASE WHEN pm.`meta_key` = '_price' THEN pm.`meta_value` END), " +
                "MAX(CASE WHEN pm.`meta_key` = '_stock' THEN pm.`meta_value` END), " +
                "p.`post_excerpt` " +
                "FROM `wp_posts` p " +
                "LEFT JOIN `wp_postmeta` pm ON p.`ID` = pm.`post_id` " +
                "WHERE p.`post_type` = 'product' AND p.`post_status` = 'publish' " +
                // --- ESTA ES LA LÍNEA MÁGICA QUE FILTRA EL STOCK ---
                "AND p.`ID` IN (SELECT `post_id` FROM `wp_postmeta` WHERE `meta_key` = '_stock_status' AND `meta_value` = 'instock') " +
                "GROUP BY p.`ID` LIMIT 20";

        try {
            List<Object[]> resultados = emWeb.createNativeQuery(sql).getResultList();

            return resultados.stream()
                    .map(r -> {
                        String nombre = String.valueOf(r[0]);
                        String precio = (r[1] != null) ? r[1].toString() : "Consultar";
                        // Si el stock numérico es nulo pero está "instock", mostramos "Disponible"
                        String stock = (r[2] != null && !r[2].toString().isEmpty()) ? r[2].toString() : "Disponible";
                        String info = (r[3] != null && !r[3].toString().isEmpty()) ? r[3].toString() : "Ver en web";

                        return String.format("Producto: %s | Precio: S/ %s | Stock: %s | Info: %s",
                                nombre, precio, stock, info);
                    })
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            return "Catálogo disponible en blessrom.com";
        }
    }
}
