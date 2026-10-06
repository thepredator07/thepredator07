# Sandbox image for factory.integrations=real: git, a shell, Python, and the Claude Code CLI.
#
#   docker build -t factory-sandbox:latest software-factory/sandbox
#
# Add the target repo's toolchain and dependencies here (or in an image built FROM this one): the sandbox has no
# internet, so checks can only use what is in the image or the repo.
FROM buildpack-deps:bookworm-scm

# Claude Code ships a self-contained native binary per platform in npm. Pinned, and verified against the integrity
# hash the npm registry publishes for that exact version (`npm view @anthropic-ai/claude-code-linux-x64@<v> dist.integrity`).
ARG CLAUDE_CODE_VERSION=2.1.291
ARG CLAUDE_CODE_SHA512_X64=i0YJxdh8yIbRQ9x1gsbB24oVWhs7BARNRIHoRjXw0CeD4Yp5JoVpQlbfPIA9Rg3UhkcH6d3StYAFrSt3l2m5HA==
ARG CLAUDE_CODE_SHA512_ARM64=lxwNznyTqaCXrOu+XSj1LowJaUbjmtk7VvL/V0SW67mLAVl0kJYBJUs9mKfN/T1xBXCFowtBLW5mhbMQY+Z0cQ==
ARG TARGETARCH

RUN set -eu; \
    case "${TARGETARCH:-amd64}" in \
      amd64) platform=linux-x64;   sha="$CLAUDE_CODE_SHA512_X64" ;; \
      arm64) platform=linux-arm64; sha="$CLAUDE_CODE_SHA512_ARM64" ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    url="https://registry.npmjs.org/@anthropic-ai/claude-code-${platform}/-/claude-code-${platform}-${CLAUDE_CODE_VERSION}.tgz"; \
    curl -fsSL -o /tmp/claude.tgz "$url"; \
    echo "$(echo "$sha" | base64 -d | od -An -v -tx1 | tr -d ' \n')  /tmp/claude.tgz" | sha512sum -c -; \
    mkdir /tmp/claude && tar -xzf /tmp/claude.tgz -C /tmp/claude; \
    install -m 0755 /tmp/claude/package/claude /usr/local/bin/claude; \
    rm -rf /tmp/claude /tmp/claude.tgz; \
    claude --version
