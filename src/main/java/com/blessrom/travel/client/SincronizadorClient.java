package com.blessrom.travel.client;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@ApplicationScoped
public class SincronizadorClient {

    @ConfigProperty(name = "blessrom.sincronizador-api.url")
    String baseUrl;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    public String buscarProductos(String consulta) {
        try {
            String queryEncoded = URLEncoder.encode(consulta, StandardCharsets.UTF_8.toString());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/v1/productos/buscar?q=" + queryEncoded))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "Catálogo temporalmente no disponible.";
        }
    }

    /**
     * Busca productos y devuelve el JSON original del buscador vectorial
     */
    public String buscarTarjetasJson(String query, int limit) {
        try {
            String body = String.format("{\"query\": \"%s\", \"limit\": %d, \"minScore\": 0.4}",
                    query.replace("\"", "\\\""), limit);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/search"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "{\"results\": []}";
        }
    }
}