package com.example.shop.catalog;

import org.springframework.data.elasticsearch.annotations.Document;

/** An Elasticsearch document written as a record. */
@Document(indexName = "suggestions")
public record SearchSuggestion(String id, String text) {}
