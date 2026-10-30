package com.example.crm;

import org.springframework.security.access.prepost.PreAuthorize;

class RoleTest {

  @PreAuthorize("hasRole('TESTER')")
  void test() {}
}
