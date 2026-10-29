package com.example.shop.app.audit;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "!cloud"})
public class LocalAudit {}
