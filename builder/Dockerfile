# The Site2App build machine. One machine runs one build and is destroyed.
#
# Everything a build needs is baked in: the JDK, the Android SDK, Node, this
# repository, and Gradle's dependencies, downloaded by building the sample app
# once here. A customer build then runs offline, which makes it faster and
# means a build cannot fetch anything except the app's icon.
#
# Build and push from the repository root:
#   flyctl deploy . --config builder/fly.toml --dockerfile builder/Dockerfile --build-only --push --image-label <tag>

FROM eclipse-temurin:21-jdk-noble AS jdk

FROM node:24-bookworm-slim

ENV JAVA_HOME=/opt/java/openjdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/opt/gradle-home \
    DEBIAN_FRONTEND=noninteractive
ENV PATH="${JAVA_HOME}/bin:${ANDROID_HOME}/cmdline-tools/latest/bin:${PATH}"

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk

# unzip for the SDK, fonts for the letter icon apply-config draws when a site has none.
RUN apt-get update \
 && apt-get install -y --no-install-recommends ca-certificates curl unzip fontconfig fonts-dejavu-core \
 && rm -rf /var/lib/apt/lists/*

# Android command-line tools, checked against the SHA-1 Google publishes, then
# the two SDK packages the shell compiles with.
ARG CMDLINE_TOOLS=commandlinetools-linux-16111833_latest.zip
ARG CMDLINE_TOOLS_SHA1=e025545c62a8e64c7559119566a569fb1dec5f60
RUN mkdir -p "${ANDROID_HOME}/cmdline-tools" \
 && curl -fsSL -o /tmp/tools.zip "https://dl.google.com/android/repository/${CMDLINE_TOOLS}" \
 && echo "${CMDLINE_TOOLS_SHA1}  /tmp/tools.zip" | sha1sum -c - \
 && unzip -q /tmp/tools.zip -d "${ANDROID_HOME}/cmdline-tools" \
 && mv "${ANDROID_HOME}/cmdline-tools/cmdline-tools" "${ANDROID_HOME}/cmdline-tools/latest" \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install "platforms;android-37.0" "build-tools;36.0.0" > /dev/null

WORKDIR /app
COPY scripts/package.json scripts/package-lock.json scripts/
RUN cd scripts && npm ci --no-audit --no-fund --omit=dev
COPY icons icons
COPY scripts scripts
COPY android android
COPY builder/run.mjs builder/run.mjs

# Warm every cache with one real build of the sample app, and keep its
# outputs: a customer build changes only resources, so Gradle reruns just
# those tasks and reuses the compiled code. The debug key signs here; a
# customer build uses the test key from secrets.
RUN cd android \
 && chmod +x gradlew \
 && ./gradlew --no-daemon --console=plain :app:assembleRelease -x lintVitalRelease

# Builds run as an unprivileged user that owns only what a build writes.
RUN useradd --create-home --uid 10001 builder \
 && chown -R builder:builder /app /opt/gradle-home
USER builder

CMD ["node", "/app/builder/run.mjs"]
