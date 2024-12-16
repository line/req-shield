# How to contribute to Req-Shield

First of all, thank you so much for taking your time to contribute! Req-Shield is not very different from any
other open source projects. It will
be fantastic if you help us by doing any of the following:

- File an issue in [the issue tracker](https://github.com/line/req-shield/issues)
  to report bugs and propose new features and improvements.
- Ask a question using [the issue tracker](https://github.com/line/req-shield/issues).
- Contribute your work by sending [a pull request](https://github.com/line/req-shield/pulls).

## Code of Conduct

We expect contributors to follow [our code of conduct](./CODE_OF_CONDUCT.md).

## Contributor license agreement

If you are sending a pull request and it's a non-trivial change beyond fixing
typos, please make sure to sign
the [ICLA (Individual Contributor License Agreement)](https://cla-assistant.io/line/req-shield).
Please [contact us](mailto:dl_oss_dev@linecorp.com) if you need the CCLA (Corporate Contributor License
Agreement).

## Things to know before you start

### Req-Shield

Req-Shield is a library that regulates the cache-based requests an application receives in terms of request-collapsing.
For more information, see the github [wiki](https://github.com/line/req-shield/wiki/What-is-Req-Shield).

### Module structure

* core
  > The module that contains the actual core unit logic<br>
  > Default implementation with blocking method

* core-reactor
  > Modules that support reactor based on actual core unit logic

* core-kotlin-coroutine
  > Modules that support kotlin-coroutine based on actual core unit logic

* core-spring
  > A module with added logic based on the actual core logic and easy to use in Spring MVC environment (Support for annotations)

* core-spring-webflux
  > A module with added logic based on the actual core logic and easy to use in Spring WebFlux environment (Support for annotations)

* core-spring-webflux-kotlin-coroutine
  > A module with added logic based on the actual core logic and easy to use in Spring WebFlux with Kotlin Coroutine environment (Support for annotations)

* support
  > Provide common code and utils used by each module

## Ways to contribute

### Reporting Bugs
Please report bugs found when using Req-Shield so we can fix them.
Please report any bugs you find on github issues.

### Suggesting Features
You can also suggest features you'd like to see added to Req-Shield.
Please report features you would like to see added to github issues.

### Pull Requests

## Developer Guide

### Development languages
* Kotlin 1.6.0 or later

### Build Requirements
* JDK 8 or later

### How To Build
* We use Gradle to build Req-Shield. The following command will compile Req-Shield.
  > ./gradlew --parallel build

### Add copyright header
* Add the following header to the top of each file.
  ```text
  Copyright $today.year LY Corporation

  LY Corporation licenses this file to you under the Apache License,
  version 2.0 (the "License"); you may not use this file except in compliance
  with the License. You may obtain a copy of the License at:

  https://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
  License for the specific language governing permissions and limitations
  under the License.
  ```

### Use meaningful exception messages
* When raising an exception, specify meaningful message which gives an explicit clue about what went wrong.
* Req-Shield manages separate CustomExceptions (e.g. ClientException) and the error types and messages are managed as separate enum classes.
  ```kotlin
  enum class ErrorCode(
    val code: String,
    val message: String
  ) {
    SUPPLIER_ERROR("1001", "An error occurred in the supplier function provided by client."),
    GET_CACHE_ERROR("1002", "An error occurred in the get cache function provided by client."),
    SET_CACHE_ERROR("1003", "An error occurred in the set cache function provided by client."),
    DOES_NOT_EXIST_GLOBAL_LOCK_FUNCTION("1004", "If isLocalLock is false, globalLockFunction must be implemented."),
    DOES_NOT_EXIST_GLOBAL_UNLOCK_FUNCTION("1005", "If isLocalLock is false, globalUnLockFunction must be implemented."),
  }
  ```

### Validate
* Validate the input parameters and throw an exception if the input is invalid.

### Prefer early-return style
* Prefer early-return style to reduce the nesting of if-else statements.
  ```kotlin
  fun exampleFunction(param: String): String {
      if (param.isEmpty()) {
        throw ClientException(ErrorCode.INVALID_PARAMETER, "param is empty.")
      }
  
      // Do something
  }
  ```

### Use ktlint
* Use ktlint to check the code style.
  > ./gradlew ktlintCheck

### Use JUnit 5 instead of JUnit 4 for testing
* Use JUnit 5 for testing.

### How to write pull request description
* Motivation
  * Explain why you're sending a pull request and what problem you're trying to solve.
* Modifications
  * List the modifications you've made in detail.
* Result
  * Closes Github issue if this resolves a reported issue.

