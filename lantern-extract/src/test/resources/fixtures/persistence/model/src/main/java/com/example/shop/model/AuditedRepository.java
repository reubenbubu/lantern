package com.example.shop.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/** Type parameters in another order than JpaRepository's, one bounded. */
@NoRepositoryBean
public interface AuditedRepository<ID, T extends BaseEntity> extends JpaRepository<T, ID> {}
