package com.example.shop.catalog;

import org.springframework.data.mongodb.core.mapping.Document;

/** A collection named at run time: not recorded. */
@Document("#{@tenant.prefix}_notes")
public class TenantNote {}
