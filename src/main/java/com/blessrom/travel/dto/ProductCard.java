package com.blessrom.travel.dto;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class ProductCard {
    private String id;
    private String name;
    private String imageUrl;
    private String url;
    private double price;
    private String priceFormatted;

    public ProductCard() {}

    public ProductCard(String id, String name, String imageUrl, String url, double price) {
        this.id = id;
        this.name = name;
        this.imageUrl = imageUrl;
        this.url = url;
        this.price = price;
        this.priceFormatted = "S/" + String.format("%.2f", price);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public double getPrice() { return price; }
    public void setPrice(double price) { this.price = price; }

    public String getPriceFormatted() { return priceFormatted; }
    public void setPriceFormatted(String priceFormatted) { this.priceFormatted = priceFormatted; }
}
