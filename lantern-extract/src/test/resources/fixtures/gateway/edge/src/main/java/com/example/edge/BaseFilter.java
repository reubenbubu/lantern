package com.example.edge;

import org.springframework.cloud.gateway.filter.GatewayFilter;

/** Not a bean and applied by no route itself: not a filter of its own. */
public abstract class BaseFilter implements GatewayFilter {}
