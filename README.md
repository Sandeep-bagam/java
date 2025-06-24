# Java Demo App

A simple Java application built with Maven for GitHub Actions practice.

## Features
- Calculator operations (add, subtract, multiply, divide)
- String utilities (reverse, palindrome check, word count)
- Unit tests with JUnit 5
- Code coverage with JaCoCo

## Build Commands
```bash
# Compile the project
mvn compile

# Run tests
mvn test

# Package the application
mvn package

# Run the application
java -cp target/classes com.example.App

# Clean build artifacts
mvn clean
```

## GitHub Actions Pipeline Features
- Automated building on push/PR
- Unit test execution
- Code coverage reporting
- Artifact creation# java
