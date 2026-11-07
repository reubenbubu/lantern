# 0129: A model groups by meaning what the code leaves ungrouped

Status: accepted (#919)

## Context

Groups come from signals the code states (ADR 0099, #535): feature folders and endpoint paths for a
unit's components, a shared database, a gateway route, a repository or a Maven group for the
system's units. On project-8 no signal forms a domain, since its services share none of them, and
12 of workorder-service's components fall in no area, so its views hold far more than the limit
of 8 plus 2. Coupling pockets (#764) filled the gap, but each was named after its central member
and one more unit could rename it, which #535 forbids. The owner directed on 6 Nov 2026 that a
model groups what the code leaves loose, as a late step after Describe, since it reads the
responsibilities Describe wrote.

The research note `docs/research/llm-grouping-by-meaning.md` (high depth) recommends one request a
level, the code's groups as fixed seeds, each element listed once by a short id with its name and
what it does, the reason before the members, one retry, and storing the groups so later runs only
place what is new. Four request variants on project-8 (6 Nov, Haiku standing in, three runs each)
compared what to send: numeric ids made no id error in 12 runs; lines between parts did not help;
the flows helped a little and gave the steadiest answers (pair precision and recall 0.83 and 0.90
on average). The owner agreed to that variant: what each part does, endpoint paths and flows, no
lines, numeric ids, and a too-large area split by code, not by the model.

## Decision

1. **One request a system, after Describe and before Name groups.** It is asked only when a view
   would hold more than the target and its tolerance: domains when the system has more containers,
   feature areas for each service with more components; nothing is asked when all of those are
   grouped already.
2. **What it shows.** Every container (`[E..] name | domain key or - | what it does`, marked when
   nothing connects to it), then, for each service too large for one view, its components
   (`[E..] name | area key or - | what it does | up to 5 endpoint paths`), then up to 40 traced
   flows that start at a listed component (or, when domains are asked, cross containers), their
   steps naming parts by reference. The responsibilities are Describe's.
3. **Membership only.** The answer places each loose part in a code group (`extends` its key) or a
   new group of at least 2, or leaves it out, each group's reason first (7 to 25 words, citing the
   parts it rests on). It names nothing: Name groups names its groups like the code's. Data stores,
   queues and topics follow the units that use them by code (C4Views), and an area too large for
   one page is split into pages by code (ADR 0117, amendment for #764).
4. **Checks.** Beside the standard ones, every member and service is a part listed, every member a
   part without a group, of the group's own scope, placed once; an extended group exists; a new group holds at least 2 such parts and its key, given once, is a new
   one (a key an existing group has extends it); a new key
   is kebab-case; domains only when asked and at most the target in all, and then every container
   something connects to in one (the first real run left two out, "no connection appears", since
   the request shows no links, and their stores and queues crowded the overview). A failed answer is asked
   once more with what failed, then nothing is grouped: the views fall back to page splits.
5. **Stored and kept.** Its groups are stored as the code's are: `group-of` and `GROUPS` facts of
   basis `meaning` (a value added to the groups' vocabulary), a new group's key
   `<scope>/<area|domain>/llm-<key>`, of origin llm `group-by-meaning`. Each run writes its earlier
   groups again from that snapshot: a member that no longer exists, or that the code now puts in a
   group of the same kind, is dropped, and a group it formed
   left with fewer than 2 members is dropped, which frees them. The step reads the groups it kept as
   fixed, so it asks only of what is still loose, and the same answer is never asked twice.
6. **Model and budget.** The reasoning tier (Sonnet in counted runs), with thinking, in the budget
   group of Name groups, whose 2% share it shares: one request a system.

## Consequences

- A group the model formed keeps its key and members across runs unless its members disappear: a
  new unit or component is placed by a new request, and nothing already grouped moves.
- The grouping is only as good as Describe's responsibilities: a part Describe left unknown is
  shown by its name and paths alone.
- A service with more areas than one view holds is not grouped a level higher; its overview may stay
  over the limit (#919 lists the follow-up).
- Early mistakes are kept. Re-opening a group on a trigger (it breaks the limit, loses half its
  members) is left for when a test set shows the need.

## Amendment for #932: shared infrastructure, and areas split by meaning

The owner's review of 6 Nov 2026 (milestone 1I) found two faults. Decision 4 made every connected
container join a domain, so edge-gateway, which routes for every domain, was forced into
"Customer accounts". And an area too large for one view was sliced by the code into views per entry
point, titled "from X and Y", which say nothing a reader would name.

1. **Shared infrastructure stays outside the domains.** A unit is shared infrastructure when it is
   a gateway (it routes requests on: `routed-endpoint` facts, or gateway routes in its modules) or
   an infrastructure server (a type of its own modules is a config, Eureka or admin server,
   `infrastructure-server`). The code's domain signals leave it out (Groups version 5); the item
   marks it "(shared infrastructure)", the check rejects an answer that puts it in a domain and no
   longer asks it to join one, and a kept membership of it in a domain is dropped. It is drawn
   beside the domains.
2. **The role splits an area too large for one view.** A feature area, of the code or of an earlier
   answer, with more members than the target and its tolerance makes its service listed, its
   components marked "(too large: split it)". The answer may move them into new areas of at least
   2 members, never into an existing area. A group formed by meaning takes its members from any
   other group of its scope, and a group left with fewer than 2 members stands for nothing
   (`Groups.of`); a kept member stays in its group by meaning while the code's group it came from
   is still too large.
3. **The code no longer slices an area by entry point.** An area that still does not fit after
   merging its lines is drawn as it is, with the scorer's warning. A unit with no areas is still
   split by groups of entry points.

The prompt is version 2.
