package com.blessrom.travel.entidades;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;

@Entity
public class Cliente extends PanacheEntity {

    public String telefono;
    @Column(columnDefinition = "TEXT")
    public String historial; // Guardamos el JSON de la charla
}

