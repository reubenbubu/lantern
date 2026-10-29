package com.example.shop.model;

import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** A base for repositories, not one itself. */
@NoRepositoryBean
public interface ShopRepository<T> extends CrudRepository<T, Long> {}
