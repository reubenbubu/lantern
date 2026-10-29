package com.example.shop.model;

import org.springframework.data.repository.RepositoryDefinition;

@RepositoryDefinition(domainClass = Van.class, idClass = Long.class)
public interface VanRepository {}
