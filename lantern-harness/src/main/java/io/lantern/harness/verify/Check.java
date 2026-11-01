package io.lantern.harness.verify;

import java.util.List;

/** One check of the pipeline: a pure function of an item's answer and its inputs. */
public interface Check {

  /** The check's name, as the run record and the logs show it. */
  String name();

  /** What the check rejects in the answer; empty if it accepts it. */
  List<Rejection> check(ItemAnswer answer, CheckInputs inputs);
}
