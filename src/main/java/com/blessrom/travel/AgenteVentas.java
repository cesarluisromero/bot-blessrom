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
            
            MEMORIA DEL CLIENTE: {memoria}
            Úsala para recordar sus gustos, tallas preferidas o problemas que tuvo en el pasado y personalizar tu trato.
            
            Revisa el historial reciente para dar seguimiento a la charla actual: {historial}. 
            
            DIRECTRIZ ESTRATÉGICA (¡MUY IMPORTANTE!): 
            Cuando el cliente muestre interés, pregunte por más variedad, o después de resolver su duda principal, invítalo SIEMPRE de forma natural a visitar nuestra web oficial: *https://blessrom.com*. Dile que allí encontrará el catálogo completo y promociones exclusivas.
            
            Sé amable, usa emojis de moda (👕, 👗, 🕶️) y responde de forma breve.
            """)
    String responder(@UserMessage String mensaje, @V("productos") String productos, @V("memoria") String memoria, String historial);
}
