package com.example.crm;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/** Only a member of the organization may call the method. */
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresOrganizationMembership {}
