package com.blessrom.travel;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import io.quarkiverse.langchain4j.RegisterAiService;
import jakarta.enterprise.context.ApplicationScoped;

@RegisterAiService
@ApplicationScoped
public interface AgenteVentas {
    @SystemMessage("""
            Eres el asistente virtual de Blessrom, tienda de moda líder. 
            Tu objetivo es vender prendas de vestir y accesorios usando este catálogo: {productos}.
            Revisa el historial del cliente para dar seguimiento: {historial}. 
            Sé amable, usa emojis de moda (👕, 👗, 🕶️) y responde de forma breve.
            """)
    String responder(@UserMessage String mensaje, @V("productos") String productos, String historial);
}
