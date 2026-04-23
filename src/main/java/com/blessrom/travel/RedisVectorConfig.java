package com.blessrom.travel;

import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class RedisVectorConfig {

    private static final Logger LOG = Logger.getLogger(RedisVectorConfig.class);

    @Inject
    RedisDataSource redis;

    void onStart(@Observes StartupEvent ev) {
        try {
            redis.execute("FT.INFO", "idx:memoria");
            LOG.info("✅ Índice vectorial 'idx:memoria' listo en Redis.");
        } catch (Exception e) {
            LOG.info("⚙️ Creando índice vectorial en Redis por primera vez...");
            redis.execute("FT.CREATE", "idx:memoria", "ON", "HASH", "PREFIX", "1", "memoria:",
                    "SCHEMA",
                    "content", "TEXT",
                    "customer_id", "TAG",
                    "content_vector", "VECTOR", "FLAT", "6",
                    "TYPE", "FLOAT32",
                    "DIM", "1536",
                    "DISTANCE_METRIC", "COSINE");
        }
    }
}