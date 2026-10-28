package com.example.activiti;

import org.activiti.api.runtime.shared.UnprocessableEntityException;

/** Compiles only against the Activiti jar from the Alfresco repository. */
public class Uses {

  RuntimeException failure(UnprocessableEntityException cause) {
    return cause;
  }
}
