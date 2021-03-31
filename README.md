# ordnungsamt
The Ordnungsamt (department of order) helps maintain order and consistency in ~society~ clojure microservices.

Largely despised, but one must wonder what society would be reduced to without it.

## context

Organizations with many relatively uniformly structured code repositories probably have the need to keep those repositories in order and adhering to the latest standards.

This is where `ordnungsamt` comes in. It is a tool that runs ad-hoc migrations over a code repository and subsequently opens a pull request on GitHub with the changes.

The rough flow is:

 - your CI schedules `ordnungsamt` to be run on every repository.
 - for a run, the CI checks out `ordnungsamt`, the repository in question, and a repository of migrations.
 - `ordnungsamt` iterates through the migrations, apply each one to the local repository checkout.
   - If the migration leads to changes, they are collected into a git commit that is posted to GitHub via their API
 - Once all migrations are applied, a GitHub pull request is opened with migration details for the repository owners to review and merge

## tests

Integration tests use an in-memory git server for mocking interactions with github.
That said, `ordnungsamt` uses the host machine's `git` binary to detect how files were changed after running migrations, so make sure you have `git` installed.

### running

via the repl or

```
clj -A:test:test-runner
```
