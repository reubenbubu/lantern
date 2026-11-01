package com.example.events;

/** A message class the sender and the listener share: its type is recorded on both (#520). */
public record OrderPlaced(String id) {}
