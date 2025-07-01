# How to Make Contributions

Thank you for considering contributing to this project! We welcome all contributions, whether it's bug reports, feature requests, documentation improvements, or code contributions.

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Setting Up Your Environment](#setting-up-your-environment)
- [Configuring Java Options](#configuring-java-options)
- [How to Build Locally](#how-to-build-locally)
- [How to Open a Pull Request](#how-to-open-a-pull-request)
- [How to Claim a Bounty](#how-to-claim-a-bounty)
- [LLM Use Guide](#llm-use-guide)
- [Code Style Guide](#code-style-guide)

## Getting Started

### Prerequisites

Before you begin, make sure you have the following installed:

- **Java 21** 
- **Scala**  
- **Node**  
- **sbt** (Scala Build Tool)  
- **Git**

### Setting Up Your Environment

1. **Fork the Repository**
   - Navigate to the **kyo** repository and click the **Fork** button.

2. **Clone Your Fork**
   ```sh
   git clone https://github.com/your-username/kyo.git
   cd your-repo
   ```

3. **Set Up Upstream Remote**
   ```sh
   git remote add upstream https://github.com/original-author/your-repo.git
   ```

## Configuring Java Options

Java options (`JAVA_OPTS` and `JVM_OPTS`) define how much memory and resources the JVM should use when running the project. Setting these options correctly ensures stable performance and prevents out-of-memory errors.

To configure Java options, run the following commands in your terminal:
```sh
export JAVA_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
export JVM_OPTS="-Xms3G -Xmx4G -Xss10M -XX:MaxMetaspaceSize=512M -XX:ReservedCodeCacheSize=128M -Dfile.encoding=UTF-8"
```

### Explanation of Parameters:

- `-Xms2G`: Sets the initial heap size to 3GB.
- `-Xmx3G`: Sets the maximum heap size to 4GB.
- `-Xss10M`: Sets the stack size to 10MB.
- `-XX:MaxMetaspaceSize=512M`: Limits the maximum metaspace size to 512MB.
- `-XX:ReservedCodeCacheSize=128M`: Reserves 128MB for compiled code caching.
- `-Dfile.encoding=UTF-8`: Ensures file encoding is set to UTF-8.

### Adjusting These Values

If you experience memory issues or your system has more resources, you can increase these values. For example, if you have 16GB RAM, you might set:
```sh
export JAVA_OPTS="-Xms4G -Xmx8G -Xss10M -XX:MaxMetaspaceSize=1G -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"
export JVM_OPTS="-Xms4G -Xmx8G -Xss10M -XX:MaxMetaspaceSize=1G -XX:ReservedCodeCacheSize=256M -Dfile.encoding=UTF-8"
```

You can also add these lines to your `.bashrc` or `.zshrc` file for persistent settings.

## How to Build Locally
Run the following commands to build and test the project locally:
```sh
sbt '+kyoJVM/test' # Runs JVM tests
sbt '+kyoJS/test'  # Runs JS tests
sbt '+kyoNative/Test/compile' # Compiles Native code
```

## How to Open a Pull Request

1. **Create a New Branch**
   ```sh
   git checkout -b feature-branch
   ```

2. **Make Your Changes**
   - Ensure your code follows project guidelines.
   - Include tests if applicable.

3. **Commit Your Changes**
   ```sh
   git add .
   git commit -m "Describe your changes"
   ```

4. **Push to Your Fork**
   ```sh
   git push origin feature-branch
   ```

5. **Create a Pull Request**
   - Navigate to your repository on GitHub.
   - Click on **Compare & pull request**.
   - Provide a clear title and description of your changes.
   - Fill out the PR template where necessary.
   - Submit the pull request for review.

## How to Claim a Bounty
- Check the list of available bounties in the **Issues** section with the `💎 Bounty` label or just cllick [here](https://github.com/getkyo/kyo/issues?q=is%3Aissue%20state%3Aopen%20label%3A%22%F0%9F%92%8E%20Bounty%22).
- Comment on the issue expressing your interest in working on it.
- Follow the contribution guidelines and submit a PR.
- Upon successful review and merge, you will receive the bounty reward.

## LLM Use Guide
We encourage contributors to leverage Large Language Models (LLMs) responsibly:
- Do **not** submit low-effort, AI-generated code without review.
- If you use AI assistance, ensure that the submission is well-tested and meets our standards.
- Automated PRs without human oversight may be closed.

## Code Style Guide
- Follow the project's existing coding style.
- Run formatting checks before submitting a PR:
  ```sh
  sbt "scalafmtCheckAll"
  ```

## Design Guidelines

### Adding a New Method

If you want to contribute a new method like `S.newMethod` or `s.newMethod`, feel free to:

- Open an issue
- Discuss on Discord: [https://discord.gg/afch62vKaW](https://discord.gg/afch62vKaW)
- Share design examples:
    - Use cases
    - Equivalent in `ZIO` or `Cats Effect`
    - Other motivating patterns

### Where to add Your `newMethod`

| Subproject        | Use For                                                 |
| ----------------- | ------------------------------------------------------- |
| `kyo-data`        | Data structures (`Chunk`, `Maybe`, `Result`, etc.)      |
| `kyo-prelude`     | Effect types without `Sync` (`Abort`, `Env`, `Var`, etc.) |
| `kyo-core`        | Methods requiring `Sync`, `Async`, or `Resource`          |
| `kyo-combinators` | Extensions or composition helpers                       |

Add corresponding tests in the same subproject.

**Example:**\
A new `Stream.fromSomething` method:

- If it uses `Sync`: place it in `kyo-core/shared/src/main/scala/kyo/StreamCoreExtensions.scala`
- If it doesn't: place it in `kyo-prelude/shared/src/main/scala/kyo/Stream.scala`

### Tips

- Prefer [`call-by-name`](https://docs.scala-lang.org/tour/by-name-parameters.html) (`body: => A < S`) for deferred evaluation **when lifting to Sync**. This works because `Sync` is the final effect to be handled, allowing proper suspension of side effects. This does not apply to other effects.

- Use [`inline`](https://docs.scala-lang.org/scala3/reference/metaprogramming/inline.html) only when beneficial in performance-sensitive APIs. Excessive use may increase compilation times.

- Keep `S` open: use `[S] => A < (S & SomeEffect)` instead of `A < SomeEffect` to support effect composition.
