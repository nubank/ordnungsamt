# ordnungsamt
The Ordnungsamt (department of order) helps maintain order and consistency in ~society~ clojure microservices.

Largely despised, but one must wonder what society would be reduced to without it.

## context

Organizations with many relatively uniformly structured code repositories probably have the need to keep those repositories in order and adhering to the latest standards.

This is where `ordnungsamt` comes in. It is a tool that runs ad-hoc migrations over a code repository and subsequently opens a pull request on GitHub with the changes.

## operation

The rough flow is:

 - your CI schedules `ordnungsamt` to be run on every repository.
 - for a run, the CI checks out `ordnungsamt`, the repository in question, and a repository of migrations.
 - `ordnungsamt` iterates through the migrations:
   - checking the `.migrations.edn` file to see if the migration has already been applied to the local repository.
   - A not-yet-applied migrations is applied to the local repository checkout.
   - When a migration leads to code alterations, they are collected into a git commit that is posted to GitHub via their API.
 - Once all migrations are applied, a GitHub pull request is opened with migration details for the repository owners to review and merge

### skipping migrations

`ordnungsamt` keeps track of what migrations it has applied in your repository using a hidden file local to the respository: `.migrations.edn`.

The `.migrations.edn` file takes the form of:

```edn
[{:id 0, :_title "Stop using deprecated xxx.yyy.zzz namespace"}
 {:id 1, :_title "Replace usage of yyy with the newer zzz"}]
```

When `ordnungsamt` opens a PR with some new migrations migration being applied, it will add the migration to that list.
To make `ordnungsamt` skip an undesired migration you can update the `.migrations.edn` file to contain an entry relating to the migration in question. `:id` is the only important field, the `:_title` serves as a human-readable helper.


## tests

Integration tests use an in-memory git server for mocking interactions with github.
That said, `ordnungsamt` uses the host machine's `git` binary to detect how files were changed after running migrations, so make sure you have `git` installed.

### running

via the repl or

```
clj -A:test:test-runner
```
