package com.example.crm;

import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Orders {

  @PreAuthorize("hasRole('MANAGER') or hasAnyRole('AUDITOR', \"SUPPORT\")")
  @GetMapping("/orders")
  public String all() {
    return "";
  }

  @Secured({"ROLE_CLERK", "ROLE_MANAGER"})
  public String clerk() {
    return "";
  }

  @RolesAllowed("ADMIN")
  public String admin() {
    return "";
  }

  // An authority that is not a role names a permission, not a kind of person.
  @PreAuthorize("hasAuthority('orders:write') or hasAuthority('ROLE_PARTNER')")
  public String write() {
    return "";
  }
}
