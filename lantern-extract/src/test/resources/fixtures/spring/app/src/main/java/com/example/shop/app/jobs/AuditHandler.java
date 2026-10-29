package com.example.shop.app.jobs;

import com.example.api.AuditRequest;
import org.jobrunr.jobs.lambdas.JobRequestHandler;
import org.springframework.stereotype.Service;

@Service
public class AuditHandler implements JobRequestHandler<AuditRequest> {

  @Override
  public void run(AuditRequest request) {}
}
