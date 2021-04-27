# ordnungsamt
The ordnungsamt (department of order) helps maintain order and consistency across GitHub-hosted code-bases.

## context

Organizations with many relatively uniformly structured code repositories probably have the need to keep those repositories in order and adhering to the latest standards.

This is where `ordnungsamt` comes in. It is a tool that runs ad-hoc migrations over a code repository and subsequently opens a pull request on GitHub with the changes.

## operation

The intented way to run `ordnungsamt` is via a build server where you can schedule `ordnungsamt` to be run over a set of GitHub hosted repositories.

For a run of `ordnungsamt` over a repository:

 - the CI should check out `ordnungsamt`, the repository in question, and a repository of migrations.
 - `ordnungsamt` closes any open GitHub pull requests that have the label `auto-refactor`
 - `ordnungsamt` iterates through the migrations:
   - checking the `.migrations.edn` file to see if the migration has already been applied to the local repository.
   - A not-yet-applied migrations is applied to the local repository checkout.
   - When a migration leads to code alterations, they are collected into a git commit that is posted to GitHub via their API.
 - Once all migrations are applied, a `auto-refactor` labeled GitHub pull request is opened with migration details for the repository owners to review and merge.

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

Integration tests use an in-memory git server for mocking interactions with GitHub.

Additionally, the `ordnungsamt` test suite and production code both makes use of the host machine's `git` binary to detect how files were changed after running migrations, so make sure you have `git` installed.

### running

via the repl or

```
clj -A:test:test-runner
```

## A Note on the libraries powering `ordnungsamt`

This project acts as an open example of how to use several other projects that Nubank uses internally:

 - [`state-flow`](https://github.com/nubank/state-flow): a Clojure framework that powers our single- and multi-service integration tests. Nubank has hundreds of Clojure microservices that are all tested using this pattern. It is being used a little bit differently here but it should still give you a feel of what the tool looks like in use.
 - [`clj-github`](https://github.com/nubank/clj-github): A Clojure library for interacting with GitHub APIs. Nubank also uses it for tools that keep all our microservice library dependencies up to date.
 - [`clj-github-mock`](https://github.com/nubank/clj-github-mock): A nice GitHub API mock that allows really nice test coverage of `ordnungamts` interactions with GitHub.
