# How to Make Contributions

Thank you for considering contributing to this project! We welcome all contributions, whether it's bug reports, feature requests, documentation improvements, or code contributions.

## Table of Contents

- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Setting Up Your Environment](#setting-up-your-environment)
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
