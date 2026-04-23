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
            {botPrompt}
            
            CATÁLOGO DE PRODUCTOS DISPONIBLES: {productos}
            
            MEMORIA DEL CLIENTE (gustos, tallas, problemas previos): {memoria}
            Úsala para personalizar tu trato con el cliente.
            
            HISTORIAL DE LA CONVERSACIÓN ACTUAL: {historial}
            Revísalo para dar seguimiento coherente a la charla.
            
            Responde de forma breve, amable y profesional.
            """)
    String responder(@UserMessage String mensaje, @V("botPrompt") String botPrompt, @V("productos") String productos, @V("memoria") String memoria, @V("historial") String historial);
}
