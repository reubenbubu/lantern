package com.example.shop.clients.feign;

/** A type the client sends and receives: recorded as its payload (#520). */
public record Product(long id, String name) {}
