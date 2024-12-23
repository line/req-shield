Visit [the github wiki](https://github.com/line/req-shield/wiki/What-is-Req-Shield) for more information.

# Req-Shield

A lib that regulates the cache-based requests an application receives in terms of request-collapsing.

## Requirements

- Java 8 or later if you are a user.
- Kotlin 1.8 or later if you are a user.
- (If link this lib with Spring) Spring Boot 2.7 (Spring Framework 5.3) or later if you are a user.

## Usage
- Choose from the following based on your platform (There is a detailed explanation in the wiki.)
`implementation("com.linecorp.cse.reqshield:core:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-reactor:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-kotlin-coroutine:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring-webflux:{version}")`<br>
`implementation("com.linecorp.cse.reqshield:core-spring-webflux-kotlin-coroutine:{version}")`<br>

## Contributing

Pull requests are welcome. For major changes, please open an issue first to discuss what you would like to
change.
Please see [CONTRIBUTING.md](CONTRIBUTING.md) for contributing to Req-Shield.

## LICENSE

Apache License 2.0

## How to reach us

- File an issue in [the issue tracker](https://github.com/line/req-shield/issues) to report a bug or suggest an
  idea.
