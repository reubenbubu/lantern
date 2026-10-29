package org.jobrunr.jobs.lambdas;

public interface JobRequestHandler<T extends JobRequest> {
  void run(T request) throws Exception;
}
