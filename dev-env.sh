# Usage: source ./dev-env.sh
# Sets up JDK 21, Android SDK and GitHub Packages credentials (from the gh CLI) for Gradle.
export JAVA_HOME="/opt/homebrew/opt/openjdk@21"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$PATH"
export GITHUB_ACTOR="$(gh api user -q .login)"
export GITHUB_TOKEN="$(gh auth token)"
