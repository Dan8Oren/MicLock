# Contributing to Mic-Lock

Thank you for your interest in contributing to Mic-Lock! We welcome contributions from the community and appreciate your help in making this project better.

## Code of Conduct

By participating in this project, you agree to maintain a respectful and inclusive environment for all contributors. Please be kind, professional, and constructive in all interactions.

## How to Report Bugs

If you encounter a bug, please help us fix it by providing detailed information:

1. **Use the Bug Report template** when creating a new issue
2. **Include device information**: Device model, Android version, app version
3. **Describe the problem**: What you expected vs. what actually happened
4. **Steps to reproduce**: Clear, step-by-step instructions
5. **Logs if possible**: Any relevant error messages or logs

## How to Suggest Features

We welcome new feature ideas! When suggesting a feature:

1. **Use the Feature Request template** when creating a new issue
2. **Describe the problem**: What issue would this feature solve?
3. **Propose a solution**: How do you envision this feature working?
4. **Consider alternatives**: Are there other ways to solve this problem?
5. **Additional context**: Any other relevant information

## Development Setup

### Prerequisites
- Android Studio (latest stable version)
- Android SDK with API 24+ support
- Git

### Setting up the development environment

1. **Clone the repository**:
   ```bash
   git clone https://github.com/yourusername/mic-lock.git
   cd mic-lock
   ```

2. **Open in Android Studio**:
   - Open Android Studio
   - Select "Open an existing Android Studio project"
   - Navigate to the cloned repository folder

3. **Build the project**:
   ```bash
   ./gradlew build
   ```

4. **Run tests**:
   ```bash
   ./gradlew test
   ./gradlew connectedAndroidTest
   ```

## Pull Request Process

1. **Fork the repository** and create your feature branch from `main`
2. **Make your changes** following the coding guidelines below
3. **Add tests** for any new functionality
4. **Ensure all tests pass** locally before submitting
5. **Write clear commit messages** describing your changes
6. **Submit a pull request** with a clear title and description

### Coding Guidelines

- **Follow Kotlin conventions**: Use standard Kotlin coding style
- **Add KDoc comments**: Document public methods and complex logic
- **Write tests**: Include unit tests for new functionality
- **Keep commits focused**: Each commit should represent a single logical change
- **Use descriptive commit messages**: Follow the format "Type: Brief description"

### Commit Message Format

```
Type: Brief description of the change

Optional longer description explaining the change in more detail,
including motivation and context.
```

Types:
- `feat`: New feature
- `fix`: Bug fix
- `docs`: Documentation changes
- `style`: Code style changes (formatting, etc.)
- `refactor`: Code refactoring
- `test`: Adding or updating tests
- `chore`: Maintenance tasks

## Device Testing

Since Mic-Lock addresses specific hardware issues, testing on real devices is crucial:

- **Primary test device**: Google Pixel 7 Pro (confirmed working)
- **Report compatibility**: Use the Device Compatibility Report template to share results from other devices
- **Include device details**: Model, Android version, microphone configuration

## Questions?

If you have questions about contributing, feel free to:
- Open an issue with the question label
- Check existing issues and discussions
- Review the [DEV_SPECS.md](DEV_SPECS.md) for technical details

Thank you for contributing to Mic-Lock!