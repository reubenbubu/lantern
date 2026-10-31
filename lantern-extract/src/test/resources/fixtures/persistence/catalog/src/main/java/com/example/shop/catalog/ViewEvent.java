package com.example.shop.catalog;

import org.springframework.data.mongodb.core.mapping.Document;

/** A MongoDB document written as a record. */
@Document(collection = "view_events")
public record ViewEvent(String id, String productId) {}
